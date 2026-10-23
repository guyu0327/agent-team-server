package com.guyu.agentteam.service;

import com.guyu.agentteam.service.tool.WorkspaceFileTools;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaSnapshotMode;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 浏览器自动化基础设施：Playwright 经 channel 驱动系统已装的 Edge（回落 Chrome），不下载浏览器。
 * 全部操作收敛到单线程执行器串行执行（Playwright API 非线程安全）；每个会话一个 BrowserContext
 * （cookie/存储隔离），共享一个浏览器进程；空闲 10 分钟的会话上下文自动关闭；应用退出整体收尾。
 * headless=false 时弹出真实浏览器窗口供用户围观 AI 操作（设置页可切换，切换后重开浏览器生效）。
 */
@Service
public class BrowserService {

    private static final Logger log = LoggerFactory.getLogger(BrowserService.class);

    public static final int IDLE_MINUTES = 10;
    private static final int PAGE_LOAD_TIMEOUT_MS = 30_000;
    private static final int ACTION_TIMEOUT_MS = 15_000;
    /** 单次操作的外层等待上限：内层 Playwright 超时（导航 30s/操作 15s）之外的兜底 */
    private static final int OP_WAIT_SECONDS = 120;
    /** 连续操作间的随机节流范围（毫秒）：模拟人的节奏，降低触发网站频控/验证码的概率 */
    private static final long THROTTLE_MIN_MS = 300;
    private static final long THROTTLE_MAX_MS = 800;
    /** 驱动进程冷启动偶发失败的重试次数 */
    private static final int CREATE_ATTEMPTS = 3;
    private static final int VIEWPORT_WIDTH = 1280;
    private static final int VIEWPORT_HEIGHT = 800;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BrowserSettingsService settings;
    private final WorkspaceFileTools fileTools;

    /** 所有 Playwright 操作串行执行（线程安全要求 + 本地单用户场景串行完全够用） */
    private final ExecutorService ops = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "browser-ops");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "browser-sweeper");
        t.setDaemon(true);
        return t;
    });

    private Playwright playwright;
    private Browser browser;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private static final class Session {
        final BrowserContext context;
        /** 当前活动标签页：点击触发 target=_blank/JS 弹新页时自动切换到新页 */
        volatile Page page;
        /** 本会话已「见过」的页面：新出现的页面即新标签页（用于自动跟随与计数） */
        final Set<Page> knownPages = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        volatile long lastUsed = System.currentTimeMillis();

        Session(BrowserContext context, Page page) {
            this.context = context;
            this.page = page;
            knownPages.add(page);
        }
    }

    /** 页面打开/操作后的快照结果：模型据此决定下一步（ref 引用元素）；unchanged=请求的页面本已打开，未重新导航 */
    public record PageInfo(String url, String title, String snapshot, boolean unchanged) {
    }

    public BrowserService(BrowserSettingsService settings, WorkspaceFileTools fileTools) {
        this.settings = settings;
        this.fileTools = fileTools;
        sweeper.scheduleAtFixedRate(this::sweepIdle, 60, 60, TimeUnit.SECONDS);
    }

    // ---------- 对 BrowserTools 暴露的操作 ----------

    /** 校验并规整 URL：仅 http/https，禁止本机/内网地址（防借浏览器打本地 API 或读内网） */
    public static String validateUrl(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(t);
        } catch (Exception e) {
            throw new IllegalArgumentException("URL 格式不正确：" + t);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("仅支持 http/https 链接：" + t);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("URL 缺少主机名：" + t);
        }
        if (host.startsWith("[")) {
            // IPv6 字面量：拦回环与链路本地
            String v6 = host.substring(1, host.length() - 1).toLowerCase();
            if (v6.startsWith("::1") || v6.startsWith("fe80:") || v6.startsWith("fc") || v6.startsWith("fd")) {
                throw new IllegalArgumentException("不允许访问本机/内网地址：" + t);
            }
        } else {
            if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("0.0.0.0")) {
                throw new IllegalArgumentException("不允许访问本机地址：" + t);
            }
            if (host.matches("127\\.\\d+\\.\\d+\\.\\d+") || host.matches("10\\.\\d+\\.\\d+\\.\\d+")
                    || host.matches("192\\.168\\.\\d+\\.\\d+") || host.matches("169\\.254\\.\\d+\\.\\d+")
                    || host.matches("172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+")) {
                throw new IllegalArgumentException("不允许访问内网地址：" + t);
            }
        }
        return uri.toString();
    }

    public PageInfo navigate(String conversationId, String url) {
        String target = validateUrl(url);
        return supply(conversationId, () -> {
            Page page = pageOf(conversationId);
            String notice = takeNotice(conversationId);
            if (sameLocation(page.url(), target)) {
                return withNotice(infoOf(conversationId, page, true), notice);
            }
            page.navigate(target);
            return withNotice(infoOf(conversationId, page, false), notice);
        });
    }

    /** 强制重新加载当前页（browser_refresh）：页面假死/交互无响应时的恢复手段；重载后元素 ref 全部失效 */
    public PageInfo reload(String conversationId) {
        return supply(conversationId, () -> {
            Page page = pageOf(conversationId);
            if (page.isClosed()) {
                throw new IllegalStateException("页面已关闭，请用 browser_open 重新打开");
            }
            page.reload();
            return infoOf(conversationId, page, false);
        });
    }

    /** 同址判断（忽略 #锚点 与末尾斜杠）：已在目标页时 browser_open 不再整页重载（保住表单/滚动状态） */
    static boolean sameLocation(String current, String target) {
        return trimForCompare(current).equals(trimForCompare(target));
    }

    private static String trimForCompare(String u) {
        if (u == null) {
            return "";
        }
        int hash = u.indexOf('#');
        String base = hash >= 0 ? u.substring(0, hash) : u;
        return base.length() > 1 && base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** 会话因空闲超时/浏览器重启被静默重建时，给下一次入口操作（open/current）附带一条状态提示 */
    private final Map<String, String> sessionNotices = new ConcurrentHashMap<>();

    private String takeNotice(String conversationId) {
        return sessionNotices.remove(conversationId);
    }

    private static PageInfo withNotice(PageInfo info, String notice) {
        if (notice == null || notice.isBlank()) {
            return info;
        }
        return new PageInfo(info.url(), info.title(), notice + "\n" + info.snapshot(), info.unchanged());
    }

    /** 点击元素；页面跳转时 Playwright 自动等待加载完成；点开新标签页时自动跟随并提示 */
    public PageInfo click(String conversationId, String ref) {
        return supply(conversationId, () -> {
            Page page = pageOf(conversationId);
            locator(page, ref).click();
            return followNewTabs(conversationId, page);
        });
    }

    /**
     * 动作后检查是否开出了新标签页（target=_blank 链接、window.open 弹窗）：
     * 有则把活动页切到最新开的一个，返回其快照并在开头附提示，让模型立刻知道发生了切换；
     * 没有则按原页面返回。新页常在 click 返回后才真正创建，所以取完原页快照（settle 顺带
     * 给了新页出现的时间）后再查一次。检查须在 ops 线程内进行（Playwright 非线程安全）。
     */
    private PageInfo followNewTabs(String conversationId, Page before) {
        Session s = sessions.get(conversationId);
        if (s == null) {
            return infoOf(before);
        }
        Page adopted = adoptNewPage(s);
        if (adopted == null) {
            PageInfo beforeInfo = infoOf(conversationId, before);
            adopted = adoptNewPage(s);
            if (adopted == null || adopted.isClosed()) {
                return beforeInfo;
            }
        } else if (adopted.isClosed()) {
            adopted = null;
        }
        if (adopted == null) {
            return infoOf(conversationId, before);
        }
        s.page = adopted;
        PageInfo info = infoOf(adopted);
        String note = "（注意：刚才的操作在新标签页打开了 " + info.url() + "，已自动切换到新标签页；"
                + "原标签页仍开着，可用 browser_tabs 查看列表、browser_select_tab 切回。）";
        return new PageInfo(info.url(), info.title(), note + "\n" + info.snapshot(), false);
    }

    /** 找出未登记的新页面（最新开的一个），登记进 knownPages 后返回；没有则返回 null */
    private Page adoptNewPage(Session s) {
        Page adopted = null;
        synchronized (s.knownPages) {
            for (Page p : s.context.pages()) {
                if (s.knownPages.add(p)) {
                    adopted = p;
                }
            }
        }
        return adopted;
    }

    /** 当前页面的 url/标题/结构快照（无会话时抛错引导先 open）；只读操作不加节流 */
    public PageInfo current(String conversationId) {
        return supply(conversationId, true, () -> {
            Session s = requireSession(conversationId);
            PageInfo info = withNotice(infoOf(conversationId, s.page, false), takeNotice(conversationId));
            String prev = lastCurrentSnapshots.put(conversationId, info.snapshot());
            if (info.snapshot().equals(prev)) {
                return new PageInfo(info.url(), info.title(),
                        info.snapshot() + "\n（页面结构与你上次调用 browser_current 时完全相同——"
                                + "重复调用不会看到新内容；要拿截断部分元素的编号，用 browser_snapshot 传附近容器编号。）",
                        info.unchanged());
            }
            return info;
        });
    }

    /** 各会话上一次 browser_current 取到的整页快照：连续相同则告知模型，避免反复重取撞运气 */
    private final Map<String, String> lastCurrentSnapshots = new ConcurrentHashMap<>();

    /**
     * 局部快照（browser_snapshot）：对 ref 指向的元素子树取可访问性结构。整页快照超长被截断时，
     * 截断点之外的元素拿不到编号——对目标附近的容器取局部结构即可获得完整子树编号，不受整页截断影响。
     * 子树本身仍超长时同样截断，提示模型换更小的容器。
     */
    public String snapshotElement(String conversationId, String ref) {
        return supply(conversationId, true, () -> {
            Page page = requireSession(conversationId).page;
            Locator loc = locator(page, ref);
            settle(page);
            tagContentImages(page);
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    return truncateSnapshot(loc.ariaSnapshot(
                            new Locator.AriaSnapshotOptions().setMode(AriaSnapshotMode.AI)));
                } catch (Exception e) {
                    if (attempt == 1) {
                        page.waitForTimeout(400);
                    } else {
                        throw new IllegalStateException("获取局部结构失败：" + brief(e));
                    }
                }
            }
            throw new IllegalStateException("获取局部结构失败");
        });
    }

    /** 填输入框；submit=true 时回车提交（可能触发跳转或开新标签页） */
    public PageInfo fill(String conversationId, String ref, String text, boolean submit) {
        return supply(conversationId, () -> {
            Page page = pageOf(conversationId);
            locator(page, ref).fill(text == null ? "" : text);
            if (submit) {
                locator(page, ref).press("Enter");
            }
            return followNewTabs(conversationId, page);
        });
    }

    public PageInfo selectOption(String conversationId, String ref, String value) {
        return supply(conversationId, () -> {
            Page page = pageOf(conversationId);
            locator(page, ref).selectOption(value == null ? "" : value);
            return followNewTabs(conversationId, page);
        });
    }

    /** 滚动结果：mode=window/container/none；before/after=滚动前后位置；max=可滚动总高度 */
    public record ScrollResult(String mode, long before, long after, long max) {
    }

    /**
     * 滚动 amount 个视口高（调用方已钳制 0.1~5，不传时为 0.9 屏）。先滚窗口；窗口不可滚（y 不动）
     * 时自动找页面上最大的可视内滚容器（overflow:auto/scroll 的元素，很多页面主内容区自带滚动条，
     * window.scrollBy 对它无效）滚它。都不可滚时 mode=none，说明页面内容不足一屏，调用方据此提示模型别反复滚。
     */
    public ScrollResult scroll(String conversationId, boolean down, double amount) {
        return supply(conversationId, () -> {
            Page page = pageOf(conversationId);
            int delta = (down ? 1 : -1) * (int) Math.round(VIEWPORT_HEIGHT * amount);
            Object r = page.evaluate("""
                    d => {
                      const before = Math.round(window.scrollY);
                      window.scrollBy(0, d);
                      const after = Math.round(window.scrollY);
                      const winMax = Math.max(0, Math.round(document.documentElement.scrollHeight - window.innerHeight));
                      if (after !== before) {
                        return { mode: 'window', before, after, max: winMax };
                      }
                      const cands = document.querySelectorAll('div,main,section,aside,article,nav,ul,ol,table,pre,textarea');
                      let best = null, bestScore = 0;
                      for (const el of cands) {
                        const oy = getComputedStyle(el).overflowY;
                        if (oy !== 'auto' && oy !== 'scroll' && oy !== 'overlay') continue;
                        if (el.scrollHeight <= el.clientHeight + 4) continue;
                        const rect = el.getBoundingClientRect();
                        if (rect.width < 200 || rect.height < 150) continue;
                        const visible = Math.min(rect.bottom, window.innerHeight) - Math.max(rect.top, 0);
                        if (visible <= 0) continue;
                        const score = el.scrollHeight + visible;
                        if (score > bestScore) { bestScore = score; best = el; }
                      }
                      if (!best) {
                        if (winMax <= 0) return { mode: 'none', before: 0, after: 0, max: 0 };
                        return { mode: 'window', before, after, max: winMax };
                      }
                      const b = best.scrollTop;
                      best.scrollTop = b + d;
                      return { mode: 'container', before: Math.round(b), after: Math.round(best.scrollTop),
                               max: Math.max(0, Math.round(best.scrollHeight - best.clientHeight)) };
                    }
                    """, delta);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) r;
            if (m == null) {
                return new ScrollResult("none", 0, 0, 0);
            }
            return new ScrollResult(
                    String.valueOf(m.get("mode")),
                    ((Number) m.getOrDefault("before", 0)).longValue(),
                    ((Number) m.getOrDefault("after", 0)).longValue(),
                    ((Number) m.getOrDefault("max", 0)).longValue());
        });
    }

    public boolean goBack(String conversationId) {
        return supply(conversationId, () -> {
            Page page = pageOf(conversationId);
            return page.goBack() != null;
        });
    }

    // ---------- 标签页管理 ----------

    /** 标签页列表项（序号 1 基，与 browser_select_tab 的 index 对应） */
    public record TabInfo(int index, String title, String url, boolean active) {
    }

    /** 列出当前会话的所有标签页（按打开顺序），顺带把未登记页面收编避免后续误报 */
    public List<TabInfo> tabs(String conversationId) {
        return supply(conversationId, true, () -> {
            Session s = requireSession(conversationId);
            List<Page> pages = absorbNewPages(s);
            List<TabInfo> out = new ArrayList<>();
            for (int i = 0; i < pages.size(); i++) {
                Page p = pages.get(i);
                out.add(new TabInfo(i + 1, safeTitle(p), p.url(), p == s.page));
            }
            return out;
        });
    }

    /** 切换活动标签页（1 基序号），返回该页的 url/标题/结构快照 */
    public PageInfo selectTab(String conversationId, int index) {
        return supply(conversationId, () -> {
            Session s = requireSession(conversationId);
            List<Page> pages = absorbNewPages(s);
            if (index < 1 || index > pages.size()) {
                throw new IllegalArgumentException("标签页编号 " + index + " 不存在，当前共 "
                        + pages.size() + " 个标签页，请先用 browser_tabs 查看列表");
            }
            Page target = pages.get(index - 1);
            s.page = target;
            PageInfo info = infoOf(conversationId, target);
            return new PageInfo(info.url(), info.title(),
                    "已切换到标签页 " + index + "。\n" + info.snapshot(), false);
        });
    }

    private Session requireSession(String conversationId) {
        Session s = sessions.get(conversationId);
        if (s == null) {
            throw new IllegalStateException("当前没有打开的网页，请先调用 browser_open");
        }
        return s;
    }

    /** 按打开顺序返回存活页面，并把未登记页面记入 knownPages（新标签页的判定基线） */
    private List<Page> absorbNewPages(Session s) {
        synchronized (s.knownPages) {
            for (Page p : s.context.pages()) {
                s.knownPages.add(p);
            }
        }
        return new ArrayList<>(s.context.pages());
    }

    /** 截图结果：字节转 ImageBlock 进模型上下文，文件路径给浏览记录前端展示缩略图 */
    public record Shot(String file, byte[] png) {
    }

    /** 截图一次两用（browser_screenshot 工具 + 操作后的浏览记录缩略图）；落盘失败不影响字节返回（file=null） */
    public Shot capture(String conversationId) {
        return capture(conversationId, null);
    }

    /** ref 非空时只截该元素（放大镜视角，细节分辨率远高于整页）；自动滚动到可视区后拍摄 */
    public Shot capture(String conversationId, String ref) {
        boolean element = ref != null && !ref.isBlank();
        return supply(conversationId, true, () -> {
            Session s = sessions.get(conversationId);
            if (s == null) {
                throw new IllegalStateException("当前没有打开的网页，请先调用 browser_open");
            }
            byte[] png = element ? shootElement(s.page, ref.trim()) : shootSettled(s.page);
            String file = null;
            try {
                Path dir = Paths.get(fileTools.primaryRoot(), "browser");
                Files.createDirectories(dir);
                Path target = dir.resolve("shot_" + LocalDateTime.now().format(TS)
                        + "_" + Long.toHexString(System.nanoTime() & 0xFFFF) + ".png");
                Files.write(target, png);
                file = target.toAbsolutePath().toString().replace('\\', '/');
            } catch (Exception e) {
                log.warn("浏览截图落盘失败：{}", e.toString());
            }
            return new Shot(file, png);
        });
    }

    /** 元素级截图：先等网络静默，滚入视口（顺带触发懒加载）后等图下载解码完再拍——
     *  图片没下完就拍是白块/半截，模型会误判「编号失效/图片不存在」；ref 失效由
     *  locator 的存在性校验当场报错引导重取快照 */
    private static byte[] shootElement(Page page, String ref) {
        settle(page);
        Locator loc = locator(page, ref);
        try {
            loc.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(3_000));
        } catch (Exception ignored) {
            // 已在视口/滚动交给截图自身处理
        }
        try {
            loc.evaluate("(el) => new Promise(res => {"
                    + " if (el.tagName !== 'IMG' || (el.complete && el.naturalWidth > 0)) return res(true);"
                    + " const started = Date.now();"
                    + " const timer = setInterval(() => {"
                    + "   const ready = el.complete && el.naturalWidth > 0;"
                    + "   if (ready || Date.now() - started > 8000) { clearInterval(timer); res(ready); } }, 300); })");
        } catch (Exception ignored) {
            // 元素中途消失等异常交给截图当场报错
        }
        // JPEG q80 而非 PNG：截图以 ImageBlock/base64 进模型上下文，PNG 一张 1-3MB，
        // 几张就把 prompt 滚爆（400 prompt too long）；清晰度由 2 倍像素密度保底
        return loc.screenshot(new Locator.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(80));
    }

    /** 截图前先等网络静默；疑似没加载出来（整页近乎空白/视口内的图全没下完）时
     *  重新等静默再重拍，最多两次——慢请求没完成就拍会得到白图，模型误判操作无效 */
    private static byte[] shootSettled(Page page) {
        byte[] png = shootOnceAfterSettle(page);
        for (int attempt = 0; attempt < 2 && looksUnloaded(page); attempt++) {
            png = shootOnceAfterSettle(page);
        }
        return png;
    }

    private static byte[] shootOnceAfterSettle(Page page) {
        settle(page);
        return page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(80));
    }

    public void closeSession(String conversationId) {
        submit(() -> {
            Session s = sessions.remove(conversationId);
            if (s != null) {
                saveState(s);
                s.context.close();
            }
        });
    }

    // ---------- 登录态持久化 ----------

    /** 「记住浏览器登录态」是否开启（browser_close 的返回文案据实描述） */
    public boolean persistLoginEnabled() {
        return settings.load().persistLogin();
    }

    /** 登录态存盘文件（persistLogin 开启时随会话关闭写入，新会话创建时恢复） */
    private Path stateFile() {
        return Paths.get(fileTools.primaryRoot(), "browser", "storage-state.json");
    }

    /** persistLogin 开启时把会话 cookie/storage 存盘：网站视角从「每次全新访客」变成「回头客」 */
    private void saveState(Session s) {
        if (!settings.load().persistLogin()) {
            return;
        }
        try {
            Files.createDirectories(stateFile().getParent());
            s.context.storageState(new BrowserContext.StorageStateOptions().setPath(stateFile()));
        } catch (Exception e) {
            log.warn("浏览器登录态保存失败：{}", e.toString());
        }
    }

    /** persistLogin 被关掉时清掉已存登录态（经 ops 线程执行避免与关闭中的保存竞态） */
    public void clearStoredLoginState() {
        submit(() -> {
            try {
                Files.deleteIfExists(stateFile());
            } catch (Exception e) {
                log.warn("清除浏览器登录态失败：{}", e.toString());
            }
        });
    }

    // ---------- 生命周期 ----------

    /** 无头/围观设置变更后调用：关掉当前浏览器，下一次操作按新模式重开 */
    public void onSettingsChanged() {
        submit(this::closeBrowserLocked);
    }

    private <T> T supply(String conversationId, java.util.function.Supplier<T> body) {
        return supply(conversationId, false, body);
    }

    /** readOnly=true 的纯查看操作（取快照/截图）不做人机节流；活跃时间照常刷新（看过页面就算还在用） */
    private <T> T supply(String conversationId, boolean readOnly, java.util.function.Supplier<T> body) {
        Future<T> f = ops.submit(() -> {
            if (!readOnly) {
                pace();
            }
            Session s = sessions.get(conversationId);
            if (s != null) {
                s.lastUsed = System.currentTimeMillis();
            }
            return body.get();
        });
        try {
            return f.get(OP_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            throw new IllegalStateException("浏览器操作超时（" + OP_WAIT_SECONDS + " 秒）");
        } catch (Exception e) {
            throw unwrap(e);
        }
    }

    private void submit(Runnable body) {
        Future<?> f = ops.submit(body);
        try {
            f.get(OP_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw unwrap(e);
        }
    }

    /** 串行队列里的随机小睡：让机器操作带上人的节奏（被打断即随取消快速退出） */
    private static void pace() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(THROTTLE_MIN_MS, THROTTLE_MAX_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("浏览器操作已取消");
        }
    }

    /** 去掉并发包装，暴露真实原因（PlaywrightException 的消息本身就是可读中文场景描述） */
    private static RuntimeException unwrap(Exception e) {
        Throwable t = e;
        while (t != null && (t instanceof java.util.concurrent.ExecutionException)) {
            t = t.getCause();
        }
        return t instanceof RuntimeException re ? re : new IllegalStateException(t);
    }

    /** 取会话页面，不存在则先建（浏览器也未启动时顺带启动）；围观窗口被用户手关时自动重建 */
    private Page pageOf(String conversationId) {
        Session stale = sessions.get(conversationId);
        if (stale != null && (stale.page.isClosed() || browser == null || !browser.isConnected())) {
            Page fallback = null;
            if (stale.page.isClosed() && browser != null && browser.isConnected()) {
                // 活动标签页被网站脚本 window.close() 关掉但还有其他标签页：把活动页切过去，会话整体保留
                fallback = stale.context.pages().stream().filter(p -> !p.isClosed()).findFirst().orElse(null);
                if (fallback != null) {
                    stale.page = fallback;
                    stale.knownPages.add(fallback);
                }
            }
            if (fallback == null) {
                sessions.remove(conversationId);
                try {
                    saveState(stale);
                } catch (Exception ignored) {
                    // 浏览器可能已死，存不了就放弃
                }
                try {
                    stale.context.close();
                } catch (Exception ignored) {
                    // 已随浏览器关闭
                }
            }
        }
        Session s = sessions.get(conversationId);
        if (s != null) {
            return s.page;
        }
        try {
            ensureBrowser();
        } catch (RuntimeException e) {
            throw new IllegalStateException(launchFailureMessage(e), e);
        }
        Browser.NewContextOptions opts = new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                // 高分屏渲染：物理像素翻倍，截图里的文字密度翻番——视觉模型会把图片降采样到
                // 内部编码分辨率，源图越清晰降采样后越可读，否则整页截图的中文小字直接糊掉
                .setDeviceScaleFactor(2)
                .setLocale("zh-CN");
        if (settings.load().persistLogin()) {
            Path state = stateFile();
            if (Files.exists(state)) {
                try {
                    opts.setStorageStatePath(state);
                    log.info("已恢复浏览器登录态：{}", state);
                } catch (Exception e) {
                    log.warn("浏览器登录态加载失败（按全新身份继续）：{}", e.toString());
                }
            }
        }
        BrowserContext context = browser.newContext(opts);
        // 网站随时可能弹新标签页（target=_blank / window.open）：新页也要配上超时与对话框自动
        // dismiss，否则新页里的 alert 会把后续工具卡死
        context.onPage(this::configurePage);
        Page page = context.newPage();
        configurePage(page);
        if (stale != null) {
            sessionNotices.put(conversationId,
                    "（注意：之前的页面已因空闲超时或浏览器重启被关闭，本次是全新会话，之前页面上的表单内容/滚动位置已丢失）");
        }
        sessions.put(conversationId, new Session(context, page));
        return page;
    }

    /** 新页面的统一初始化：导航/操作超时 + alert/confirm/beforeunload 一律 dismiss（不处理会卡死工具） */
    private void configurePage(Page page) {
        page.setDefaultNavigationTimeout(PAGE_LOAD_TIMEOUT_MS);
        page.setDefaultTimeout(ACTION_TIMEOUT_MS);
        page.onDialog(Dialog::dismiss);
    }

    private void ensureBrowser() {
        if (browser != null && browser.isConnected()) {
            return;
        }
        boolean headless = settings.load().headless();
        if (playwright == null) {
            // 冷启动偶发「Failed to create driver」：短间隔重试几次即可成功
            RuntimeException last = null;
            for (int attempt = 1; attempt <= CREATE_ATTEMPTS && playwright == null; attempt++) {
                try {
                    playwright = Playwright.create();
                } catch (RuntimeException e) {
                    last = e;
                    log.warn("Playwright 驱动初始化失败（第 {}/{} 次）：{}", attempt, CREATE_ATTEMPTS, e.toString());
                    if (attempt < CREATE_ATTEMPTS) {
                        try {
                            Thread.sleep(1000L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            if (playwright == null) {
                throw new IllegalStateException("Playwright 驱动初始化失败（已重试 " + CREATE_ATTEMPTS + " 次）："
                        + brief(last), last);
            }
        }
        BrowserType chromium = playwright.chromium();
        List<String> tried = new ArrayList<>();
        for (String channel : List.of("msedge", "chrome")) {
            try {
                browser = chromium.launch(new BrowserType.LaunchOptions()
                        .setChannel(channel)
                        .setHeadless(headless)
                        // 去掉 navigator.webdriver=true 标记：网站风控据此把访问直接判成机器人
                        .setArgs(List.of("--disable-blink-features=AutomationControlled")));
                log.info("浏览器自动化已启动：channel={} headless={}", channel, headless);
                return;
            } catch (RuntimeException e) {
                log.warn("浏览器启动失败 channel={} headless={}", channel, headless, e);
                tried.add(channel + "（" + brief(e) + "）");
            }
        }
        playwright.close();
        playwright = null;
        throw new IllegalStateException("系统未找到可用的 Edge/Chrome：" + String.join("；", tried)
                + "。请安装 Microsoft Edge 或 Google Chrome 后重试。");
    }

    /** 关闭全部会话与浏览器（设置变更重开、应用退出时调用）；在 ops 线程内执行 */
    private void closeBrowserLocked() {
        for (String id : List.copyOf(sessions.keySet())) {
            Session s = sessions.remove(id);
            if (s != null) {
                try {
                    saveState(s);
                } catch (Exception ignored) {
                    // 浏览器可能已被用户手关
                }
                try {
                    s.context.close();
                } catch (Exception ignored) {
                    // 同上
                }
            }
        }
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception ignored) {
                // 同上
            }
            browser = null;
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception ignored) {
                // driver 进程退出失败不阻塞收尾
            }
            playwright = null;
        }
    }

    private void sweepIdle() {
        long deadline = System.currentTimeMillis() - IDLE_MINUTES * 60_000L;
        List<String> idle = new ArrayList<>();
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            if (e.getValue().lastUsed < deadline) {
                idle.add(e.getKey());
            }
        }
        for (String id : idle) {
            ops.submit(() -> {
                Session s = sessions.remove(id);
                if (s != null) {
                    saveState(s);
                    try {
                        s.context.close();
                    } catch (Exception ignored) {
                        // 已被关闭
                    }
                }
            });
        }
    }

    @PreDestroy
    void shutdown() {
        sweeper.shutdownNow();
        try {
            ops.submit(this::closeBrowserLocked).get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 退出收尾尽力而为
        }
        ops.shutdownNow();
    }

    // ---------- 工具方法 ----------

    private static Locator locator(Page page, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("ref 不能为空，请从最近一次返回的页面结构快照中取 [ref=…] 的值");
        }
        Locator l = page.locator("aria-ref=" + ref.trim());
        // 立即校验存在性：失效编号当场报错，而不是等 Playwright 15 秒超时
        if (l.count() == 0) {
            throw new IllegalArgumentException("元素编号 " + ref.trim() + " 不存在或已失效（页面可能已跳转/刷新），"
                    + "请重新获取页面结构快照并使用其中的 [ref=…]");
        }
        return l;
    }

    /** 动作已成功但快照抢跑导航失败时的降级提示（不谎报动作失败，引导模型换途径查看） */
    private static final String SNAPSHOT_UNAVAILABLE = "（页面快照暂不可用：刚才的动作本身可能已成功并触发了跳转，"
            + "可用 browser_screenshot 查看当前画面，或稍后用 browser_current 重取结构）";

    private PageInfo infoOf(Page page) {
        return infoOf(page, false);
    }

    /** infoOf + 标签页计数后缀：多于 1 个标签页时在快照尾部提示，模型不再对多标签页毫无感知 */
    private PageInfo infoOf(String conversationId, Page page, boolean unchanged) {
        PageInfo info = infoOf(page, unchanged);
        Session s = sessions.get(conversationId);
        int n = s == null ? 1 : s.context.pages().size();
        if (n <= 1) {
            return info;
        }
        return new PageInfo(info.url(), info.title(),
                info.snapshot() + "\n（浏览器当前共 " + n
                        + " 个标签页，可用 browser_tabs 查看列表、browser_select_tab 切换）",
                info.unchanged());
    }

    private PageInfo infoOf(String conversationId, Page page) {
        return infoOf(conversationId, page, false);
    }

    private PageInfo infoOf(Page page, boolean unchanged) {
        try {
            // 动作可能触发跳转：等主导航落定再取快照，避免抓到正在销毁的旧文档
            page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(10_000));
        } catch (Exception ignored) {
            // 长连接页面 load 迟迟不触发：按当前已渲染内容继续
        }
        settle(page);
        String url = page.url();
        String title = safeTitle(page);
        return new PageInfo(url, title, snapshotWithRetry(page), unchanged);
    }

    /**
     * SPA 页面 load 之后还要发请求拉数据、异步渲染（贴吧/小黑盒等），
     * load 一到就截图/取快照会拍到骨架屏。等待网络静默兜住这类页面；
     * 长轮询/统计打点页永远不会静默，超时按当前状态继续（尽力而为）。
     */
    private static void settle(Page page) {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(3_500));
        } catch (Exception ignored) {
            // 网络一直不安静：不无限等
        }
    }

    /** 疑似还没渲染出来，用于触发截图重拍：正文可见文本过短且可交互元素过少（页面壳都
     *  没有），或视口内所有的图都没下载解码完（壳已渲染但内容请求没回来，拍到半白页） */
    private static boolean looksUnloaded(Page page) {
        try {
            Object r = page.evaluate(
                    "() => { const b = document.body; if (!b) return true;"
                            + " const text = (b.innerText || '').trim().length;"
                            + " const vis = b.querySelectorAll('a,button,input,[role=button],img').length;"
                            + " if (text < 30 && vis < 5) return true;"
                            + " const inView = Array.from(document.images).filter(i => {"
                            + "   const r = i.getBoundingClientRect();"
                            + "   return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < window.innerHeight; });"
                            + " return inView.length > 0 && inView.every(i => !i.complete || i.naturalWidth === 0); }");
            return Boolean.TRUE.equals(r);
        } catch (Exception e) {
            return false;
        }
    }

    private static String safeTitle(Page page) {
        try {
            return page.title();
        } catch (Exception e) {
            return "";
        }
    }

    /** 快照兜底：导航竞态下 body 可能瞬间不存在，等一拍重试一次，仍失败则降级为软提示 */
    private String snapshotWithRetry(Page page) {
        tagContentImages(page);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // AI 模式才会在快照里输出 [ref=eN] 元素编号，click/fill/select 全靠它引用元素；
                // 超 2 万字符截断——长帖/图片海页面单页快照可达十几万字符，每步工具结果都留在
                // 当轮 ReAct 记忆里无上限累积，几步就把 prompt 滚到百万 token 直接 400
                return truncateSnapshot(page.locator("body")
                        .ariaSnapshot(new Locator.AriaSnapshotOptions().setMode(AriaSnapshotMode.AI)));
            } catch (Exception e) {
                if (attempt == 1) {
                    page.waitForTimeout(600);
                } else {
                    log.info("页面快照重试后仍失败（动作已执行，导航竞态或特殊页面）：{}", e.toString());
                    return SNAPSHOT_UNAVAILABLE;
                }
            }
        }
        return SNAPSHOT_UNAVAILABLE;
    }

    /**
     * 给页面图片挂无障碍标记，使其进入快照并获得 [ref=]，模型可 browser_screenshot(ref=) 定点看图。
     * 不筛尺寸：正文图、头像、小表情都标记（标签里带宽高，模型可自行判断类型），
     * 只跳过尚未加载/不可见的（宽高 <2）；最多 {@value #MAX_TAGGED_IMAGES} 张，失败静默降级。
     */
    private static final int MAX_TAGGED_IMAGES = 40;

    private void tagContentImages(Page page) {
        try {
            page.evaluate(""
                    + "() => { let n = 0;"
                    + " for (const im of document.querySelectorAll('img')) {"
                    + " if (n >= " + MAX_TAGGED_IMAGES + ") break;"
                    + " const w = im.clientWidth || im.naturalWidth, h = im.clientHeight || im.naturalHeight;"
                    + " if (w < 2 || h < 2) continue;"
                    + " const alt = ((im.getAttribute('alt') || '') + '').trim();"
                    + " im.setAttribute('role', 'img');"
                    + " im.setAttribute('aria-label', '图片' + (++n) + ' ' + w + 'x' + h"
                    + " + (alt ? '：' + alt.slice(0, 30) : ''));"
                    + " } return n; }");
        } catch (Exception e) {
            log.debug("内容图标记失败（不影响快照）：{}", e.toString());
        }
    }

    private static String truncateSnapshot(String snapshot) {
        if (snapshot == null) {
            return "（页面无可访问性结构）";
        }
        if (snapshot.length() <= 20000) {
            return snapshot;
        }
        return snapshot.substring(0, 20000)
                + "\n…[页面结构过长已截断，可用 browser_snapshot 传目标附近元素的编号取局部结构"
                + "（选尽量小的容器），或 browser_scroll 配合 browser_screenshot 查看]";
    }

    private static String launchFailureMessage(RuntimeException e) {
        return "浏览器启动失败：" + e.getMessage();
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : (m.length() > 120 ? m.substring(0, 120) + "…" : m);
    }
}
