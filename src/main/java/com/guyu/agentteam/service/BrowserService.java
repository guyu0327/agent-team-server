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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
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
        final Page page;
        volatile long lastUsed = System.currentTimeMillis();

        Session(BrowserContext context, Page page) {
            this.context = context;
            this.page = page;
        }
    }

    /** 页面打开/操作后的快照结果：模型据此决定下一步（ref 引用元素） */
    public record PageInfo(String url, String title, String snapshot) {
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
            page.navigate(target);
            return infoOf(page);
        });
    }

    /** 点击元素；页面跳转时 Playwright 自动等待加载完成 */
    public PageInfo click(String conversationId, String ref) {
        return supply(conversationId, () -> {
            Page page = pageOf(conversationId);
            locator(page, ref).click();
            return infoOf(page);
        });
    }

    /** 当前页面的 url/标题/结构快照（无会话时抛错引导先 open） */
    public PageInfo current(String conversationId) {
        return supply(conversationId, () -> {
            Session s = sessions.get(conversationId);
            if (s == null) {
                throw new IllegalStateException("当前没有打开的网页，请先调用 browser_open");
            }
            return infoOf(s.page);
        });
    }

    /** 填输入框；submit=true 时回车提交（可能触发跳转） */
    public PageInfo fill(String conversationId, String ref, String text, boolean submit) {
        return supply(conversationId, () -> {
            Page page = pageOf(conversationId);
            locator(page, ref).fill(text == null ? "" : text);
            if (submit) {
                locator(page, ref).press("Enter");
            }
            return infoOf(page);
        });
    }

    public PageInfo selectOption(String conversationId, String ref, String value) {
        return supply(conversationId, () -> {
            Page page = pageOf(conversationId);
            locator(page, ref).selectOption(value == null ? "" : value);
            return infoOf(page);
        });
    }

    /** 返回滚动后的纵向位置（像素） */
    public long scroll(String conversationId, boolean down) {
        return supply(conversationId, () -> {
            Page page = pageOf(conversationId);
            int delta = (down ? 1 : -1) * VIEWPORT_HEIGHT * 9 / 10;
            Object y = page.evaluate(
                    "d => { window.scrollBy(0, d); return Math.round(window.scrollY); }", delta);
            return y instanceof Number n ? n.longValue() : 0L;
        });
    }

    public boolean goBack(String conversationId) {
        return supply(conversationId, () -> {
            Page page = pageOf(conversationId);
            return page.goBack() != null;
        });
    }

    /** 截图结果：字节转 ImageBlock 进模型上下文，文件路径给浏览记录前端展示缩略图 */
    public record Shot(String file, byte[] png) {
    }

    /** 截图一次两用（browser_screenshot 工具 + 操作后的浏览记录缩略图）；落盘失败不影响字节返回（file=null） */
    public Shot capture(String conversationId) {
        return supply(conversationId, () -> {
            Session s = sessions.get(conversationId);
            if (s == null) {
                throw new IllegalStateException("当前没有打开的网页，请先调用 browser_open");
            }
            byte[] png = shootSettled(s.page);
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

    /** 截图前先等网络静默；疑似没加载出来（整页近乎空白）时等一拍重拍，最多两次 */
    private static byte[] shootSettled(Page page) {
        settle(page);
        byte[] png = page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG));
        for (int attempt = 0; attempt < 2 && looksUnloaded(page); attempt++) {
            page.waitForTimeout(1_500);
            png = page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG));
        }
        return png;
    }

    public void closeSession(String conversationId) {
        submit(() -> {
            Session s = sessions.remove(conversationId);
            if (s != null) {
                s.context.close();
            }
        });
    }

    // ---------- 生命周期 ----------

    /** 无头/围观设置变更后调用：关掉当前浏览器，下一次操作按新模式重开 */
    public void onSettingsChanged() {
        submit(this::closeBrowserLocked);
    }

    private <T> T supply(String conversationId, java.util.function.Supplier<T> body) {
        Future<T> f = ops.submit(() -> {
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
            sessions.remove(conversationId);
            try {
                stale.context.close();
            } catch (Exception ignored) {
                // 已随浏览器关闭
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
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setLocale("zh-CN"));
        Page page = context.newPage();
        page.setDefaultNavigationTimeout(PAGE_LOAD_TIMEOUT_MS);
        page.setDefaultTimeout(ACTION_TIMEOUT_MS);
        // alert/confirm/beforeunload 不处理会让工具卡死：一律 dismiss
        page.onDialog(Dialog::dismiss);
        sessions.put(conversationId, new Session(context, page));
        return page;
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
                        .setHeadless(headless));
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
                    s.context.close();
                } catch (Exception ignored) {
                    // 浏览器可能已被用户手关
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
        try {
            // 动作可能触发跳转：等主导航落定再取快照，避免抓到正在销毁的旧文档
            page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(10_000));
        } catch (Exception ignored) {
            // 长连接页面 load 迟迟不触发：按当前已渲染内容继续
        }
        settle(page);
        String url = page.url();
        String title = safeTitle(page);
        return new PageInfo(url, title, snapshotWithRetry(page));
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

    /** 疑似还没渲染出来（正文可见文本过短且可交互元素过少），用于触发截图重拍 */
    private static boolean looksUnloaded(Page page) {
        try {
            Object r = page.evaluate(
                    "() => { const b = document.body; if (!b) return true;"
                            + " const text = (b.innerText || '').trim().length;"
                            + " const vis = b.querySelectorAll('a,button,input,[role=button],img').length;"
                            + " return text < 30 && vis < 5; }");
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
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // AI 模式才会在快照里输出 [ref=eN] 元素编号，click/fill/select 全靠它引用元素
                return page.locator("body")
                        .ariaSnapshot(new Locator.AriaSnapshotOptions().setMode(AriaSnapshotMode.AI));
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

    private static String truncateSnapshot(String snapshot) {
        if (snapshot == null) {
            return "（页面无可访问性结构）";
        }
        if (snapshot.length() <= 20000) {
            return snapshot;
        }
        return snapshot.substring(0, 20000)
                + "\n…[页面结构过长已截断，可用 browser_scroll 配合 browser_screenshot 查看局部]";
    }

    private static String launchFailureMessage(RuntimeException e) {
        return "浏览器启动失败：" + e.getMessage();
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : (m.length() > 120 ? m.substring(0, 120) + "…" : m);
    }
}
