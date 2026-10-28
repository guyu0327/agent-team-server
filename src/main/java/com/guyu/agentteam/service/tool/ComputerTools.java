package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.entity.OperationGrant;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Service;

import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/**
 * 本机键鼠操控工具（java.awt.Robot，零外部依赖）：截屏给模型视觉定位坐标，再移动/点击/按键/打字。
 * 两段式定位治「点歪」：全屏截图统一降采样（长边≤1568 且总像素≤115 万，取更严者）后回传并如实报告分辨率
 * ——低于各家视觉 API 的静默重采样阈值，任何模型所见即所报；computer_zoom 按粗坐标从缓存的原始分辨率
 * 截图裁局部放大回传（不降采样），小按钮/密集文字看清后再精调坐标；点击自动回带落点周围原始分辨率
 * 局部核对图（光标在图中心附近）。坐标一律基于「最近一次回传的图」，后端按参照系（全屏缩略图乘比例 /
 * 局部放大图加偏移）自动换算到真实屏幕。点击前 mouseMove 后停顿一拍，给 hover 展开型 UI 留反应时间。
 * computer_screenshot / computer_zoom 只读不审批；点击/按键/打字均受控操作（审批卡，人不在即拒绝）。
 * 打字走系统剪贴板 + Ctrl+V（Robot keycode 无法输入中文），会覆盖用户剪贴板——审批详情里明示。
 * 错误一律以中文可读文本返回（不抛出），让智能体能读到并向用户转述。
 */
@Service
public class ComputerTools {

    /** 受控操作被拒绝时返回给模型的工具结果 */
    private static final String DENIED =
            "【未获批准】用户没有允许这次键鼠操作，操作未执行。请尊重用户的决定，不要重复请求；"
                    + "如确有需要，请在回复中说明要做什么、为什么，等用户同意后再试。";

    /** 截图回传上限：长边 ≤1568 且总像素 ≤115 万（取更严者）——低于 DeepSeek/OpenAI/Anthropic/Gemini 的二次压缩线 */
    private static final int SHOT_MAX_EDGE = 1568;
    private static final double SHOT_MAX_PIXELS = 1_150_000;

    /** 局部放大图边长上限（原始分辨率像素）：1000×1000 = 100 万像素，仍在回传预算内、不触发二次降采样 */
    private static final int ZOOM_MAX_SIDE = 1000;
    private static final int ZOOM_MIN_SIDE = 64;

    /** 点击后核对图边长（原始分辨率像素）：光标就在图中心附近，小按钮偏没偏一眼可见 */
    private static final int VERIFY_CROP_SIDE = 400;

    /** 点击前 mouseMove 后的停顿：给 hover 展开型 UI（菜单、下拉）留出反应时间，避免点到展开前的界面 */
    private static final long HOVER_PAUSE_MS = 300;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final WorkspaceFileTools fileTools;

    /** 懒加载单例：Robot 依赖非 headless 环境；synchronize 串行化避免多方同时抢键鼠 */
    private volatile Robot robot;

    /**
     * 模型坐标参照系：所有 computer_* 坐标基于「最近一次回传的图」。
     * zoom=false：全屏降采样图，尺寸 {w,h}，点击坐标乘 屏幕尺寸/图尺寸 换算；
     * zoom=true：局部放大图（原始分辨率 1:1），左上角对应屏幕 (ox,oy)，点击坐标加偏移即屏幕坐标。
     */
    private record RefFrame(boolean zoom, int w, int h, int ox, int oy) {
    }

    /** 最近一次回传图的参照系 */
    private volatile RefFrame ref;

    /** 最近一次全屏截图的原始分辨率版本（zoom 裁剪与点击核对图的数据源） */
    private volatile BufferedImage lastOriginal;

    public ComputerTools(WorkspaceFileTools fileTools) {
        this.fileTools = fileTools;
    }

    /** 拼进智能体 system prompt 的键鼠工具使用说明（单聊/成员、编排者、被委派成员三个入口共用） */
    public String promptNote() {
        StringBuilder sb = new StringBuilder("本机键鼠工具说明：computer_* 系列直接操控这台电脑的键盘鼠标，")
                .append("用于浏览器工具覆盖不到的场景（桌面应用、浏览器网页里点不动的按钮、系统弹窗等）。")
                .append("推荐流程：① computer_screenshot 看全屏，锁定目标的大致位置；")
                .append("② 目标是小按钮/图标/密集文字等看不清的元素时，先 computer_zoom 放大该区域看清再定坐标")
                .append("（大按钮等清晰目标可跳过）；③ computer_click 点击，坐标一律基于「最近一次回传的图」上的像素")
                .append("直接给出（后端自动换算到真实屏幕，切勿自行缩放）；点击后自动回带落点周围的放大核对图")
                .append("（光标就在图中心附近），偏了就按全屏图坐标小步修正，不明确就再 zoom；")
                .append("④ 需要全局视角、或点击后画面已变化（弹窗/跳转），先 computer_screenshot 再操作。")
                .append("点击瞄准元素的几何中心（文字按钮对准文字中央，图标按钮对准图标中央）。")
                .append("computer_key 传组合键（如 ctrl+s、alt+tab、enter）；computer_type 输入中文/长文本")
                .append("（走剪贴板粘贴，会覆盖系统剪贴板）。注意：你的操作会抢占用户的键盘鼠标焦点，")
                .append("用户此刻可能正在打字——每次点击/按键/打字都要先经用户审批，一次只做一小步，")
                .append("做一步核对一次，不要盲操作连点。能网抓/读网页就不动键鼠。");
        return sb.toString();
    }

    /** 会话级一套工具实例：conversationId 供截图落盘到该会话工作区 */
    public void register(Toolkit toolkit, Supplier<String> conversationId, OpRequestSink sink) {
        toolkit.registerTool(new ComputerTool(conversationId, sink));
    }

    public class ComputerTool {

        private final Supplier<String> conversationId;
        private final OpRequestSink sink;

        ComputerTool(Supplier<String> conversationId, OpRequestSink sink) {
            this.conversationId = conversationId;
            this.sink = sink;
        }

        @Tool(name = "computer_screenshot", description = "Take a screenshot of the whole physical screen so "
                + "you can actually see the user's desktop. ALWAYS call this first before any mouse/keyboard "
                + "action. The image may be downscaled; its exact resolution is stated in the reply and click "
                + "coordinates are rescaled automatically. Also call this again when you need a full-screen "
                + "view again after computer_zoom, or when the screen has changed after a click (dialog, "
                + "navigation).")
        public ToolResultBlock computerScreenshot() {
            try {
                Shot shot = shotPng();
                Path file = saveShot(shot.png());
                TextBlock note = TextBlock.builder()
                        .text("当前屏幕截图（分辨率 " + shot.width() + "×" + shot.height()
                                + "，坐标参照系已切换到本图：点击/放大都按此图上的像素给出，后端自动换算到真实屏幕）"
                                + (file != null ? "，已存：" + file : "") + "。请直接描述或分析画面。")
                        .build();
                return ToolResultBlock.of(List.of(note, imageBlock(shot.png())));
            } catch (Exception e) {
                return ToolResultBlock.error("截屏失败：" + msg(e));
            }
        }

        @Tool(name = "computer_zoom", description = "Crop and enlarge a region around (x, y) from the most "
                + "recent screenshot at FULL original resolution (no downscaling), so small buttons, icons "
                + "and dense text become clearly visible. Coordinates are pixels on the most recent image "
                + "(same reference as computer_click). size is the region side in pixels on that image "
                + "(200-800 recommended). Read-only, no approval needed. Call this before clicking a small "
                + "or unclear target, then give click coordinates in the returned zoomed image's pixels.")
        public ToolResultBlock computerZoom(
                @ToolParam(name = "x", description = "Pixel x of the region center on the most recent image") int x,
                @ToolParam(name = "y", description = "Pixel y of the region center on the most recent image") int y,
                @ToolParam(name = "size", required = false,
                        description = "Region side in pixels on the most recent image; omit for 400")
                Integer size) {
            BufferedImage original = lastOriginal;
            RefFrame from = ref;
            if (original == null || from == null) {
                return ToolResultBlock.error("还没有可放大的截图，请先调用 computer_screenshot。");
            }
            int side = size == null ? 400 : size;
            if (side < 32 || side > 2000) {
                return ToolResultBlock.error("放大失败：size 需在 32~2000 之间（参考 200~800）。");
            }
            try {
                synchronized (ComputerTools.this) {
                    // 参照图坐标 → 真实屏幕坐标：全屏图乘比例（区域边长同比缩放），局部放大图 1:1
                    java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
                    double scale = from.zoom() ? 1.0 : (double) screen.width / from.w();
                    double realCx = from.zoom() ? from.ox() + x : x * scale;
                    double realCy = from.zoom() ? from.oy() + y : y * scale;
                    double realSide = Math.max(ZOOM_MIN_SIDE, Math.min(ZOOM_MAX_SIDE, side * scale));
                    int w = (int) Math.min(realSide, original.getWidth());
                    int h = (int) Math.min(realSide, original.getHeight());
                    int ox = clamp((int) Math.round(realCx) - w / 2, 0, original.getWidth() - w);
                    int oy = clamp((int) Math.round(realCy) - h / 2, 0, original.getHeight() - h);
                    BufferedImage crop = original.getSubimage(ox, oy, w, h);
                    byte[] png = toPng(crop);
                    ref = new RefFrame(true, w, h, ox, oy);
                    Path file = saveShot(png);
                    // 全屏缩略图上对应位置，帮模型把局部图放回全局语境
                    int[] thumb = thumbDims(original.getWidth(), original.getHeight());
                    int mapX = (int) Math.round((ox + w / 2.0) / original.getWidth() * thumb[0]);
                    int mapY = (int) Math.round((oy + h / 2.0) / original.getHeight() * thumb[1]);
                    TextBlock note = TextBlock.builder()
                            .text("局部放大图（原始分辨率 " + w + "×" + h + "，与真实屏幕 1:1；对应屏幕区域左上角 ("
                                    + ox + ", " + oy + ")，位于全屏缩略图中心附近 (" + mapX + ", " + mapY + ")）"
                                    + (file != null ? "，已存：" + file : "")
                                    + "。坐标参照系已切换到本图：后续点击按本图上的像素直接给出，后端自动换算。")
                            .build();
                    return ToolResultBlock.of(List.of(note, imageBlock(png)));
                }
            } catch (Exception e) {
                return ToolResultBlock.error("放大失败：" + msg(e));
            }
        }

        @Tool(name = "computer_click", description = "Click at pixel (x, y) taken from the most recent "
                + "returned image: the full-screen screenshot OR the latest computer_zoom crop (each tool "
                + "reply states which reference is active; the backend rescales to the physical screen "
                + "automatically). Coordinates MUST come from that image. Prefer aiming at the geometric "
                + "center of the target element. Requires user approval; an original-resolution crop "
                + "centered on the landing point is returned afterwards so you can verify precisely where "
                + "the cursor landed (the verify crop itself does NOT change the reference).")
        public ToolResultBlock computerClick(
                @ToolParam(name = "x", description = "Pixel x on the most recent returned image") int x,
                @ToolParam(name = "y", description = "Pixel y on the most recent returned image") int y,
                @ToolParam(name = "doubleClick", required = false,
                        description = "true for double-click; omit for single click")
                Boolean doubleClick) {
            boolean dbl = Boolean.TRUE.equals(doubleClick);
            String desc = (dbl ? "双击" : "单击") + "图像坐标 (" + x + ", " + y + ")";
            if (!sink.request(OperationGrant.OP_COMPUTER, desc,
                    "即将移动并点击鼠标，期间请勿操作键鼠；坐标基于最近一次回传图像估算。")) {
                return ToolResultBlock.error(DENIED);
            }
            java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            RefFrame f = ref;
            int cx;
            int cy;
            if (f == null) {
                cx = x;
                cy = y;
            } else if (f.zoom()) {
                cx = f.ox() + x;
                cy = f.oy() + y;
            } else {
                cx = (int) Math.round(x * (double) screen.width / f.w());
                cy = (int) Math.round(y * (double) screen.height / f.h());
            }
            cx = Math.max(0, Math.min(cx, screen.width - 1));
            cy = Math.max(0, Math.min(cy, screen.height - 1));
            try {
                BufferedImage original;
                synchronized (ComputerTools.this) {
                    Robot r = robot();
                    r.mouseMove(cx, cy);
                    r.waitForIdle();
                    Thread.sleep(HOVER_PAUSE_MS);
                    r.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                    if (dbl) {
                        r.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                        r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                    }
                    r.waitForIdle();
                    original = freshOriginal();
                }
                // 核对图：落点周围原始分辨率局部（光标就在中心附近），参照系不切换——模型下一步仍按全屏图坐标
                BufferedImage crop = cropAround(original, cx, cy, VERIFY_CROP_SIDE);
                byte[] png = toPng(crop);
                Path file = saveShot(png);
                TextBlock note = TextBlock.builder()
                        .text("已" + (dbl ? "双击" : "单击") + "屏幕：图像坐标 (" + x + ", " + y + ") → 真实屏幕 (" + cx + ", " + cy
                                + ")。落点周围放大核对图（原始分辨率 " + crop.getWidth() + "×" + crop.getHeight()
                                + "，光标在图中心附近，用于核对点没点准；参照系不变，仍按全屏图坐标操作）"
                                + (file != null ? "，已存：" + file : "")
                                + "。请核对：光标是否落在目标元素上、点击是否生效。若画面已变化（弹窗/跳转）"
                                + "或需要全局视角，先 computer_screenshot 再继续。")
                        .build();
                return ToolResultBlock.of(List.of(note, imageBlock(png)));
            } catch (Exception e) {
                return ToolResultBlock.error(desc + "失败：" + msg(e));
            }
        }

        @Tool(name = "computer_key", description = "Press a key or key combination on the physical keyboard, "
                + "e.g. \"enter\", \"ctrl+s\", \"alt+tab\", \"ctrl+shift+t\". Use it to trigger shortcuts, "
                + "confirm dialogs or submit forms in desktop apps. Requires user approval.")
        public String computerKey(
                @ToolParam(name = "combo",
                        description = "Key combo, modifiers joined by '+', e.g. ctrl+s / alt+tab / enter / f5")
                String combo) {
            String target = combo == null ? "" : combo.trim();
            if (target.isEmpty()) {
                return "按键失败：combo 不能为空。";
            }
            int[] parsed = parseCombo(target.toLowerCase(Locale.ROOT));
            if (parsed == null) {
                return "按键失败：无法识别按键「" + target + "」。支持 ctrl/alt/shift/win 修饰键与 enter/esc/tab/space/"
                        + "backspace/delete/home/end/pageup/pagedown/上下左右/f1~f12/字母数字/常见符号。";
            }
            if (!sink.request(OperationGrant.OP_COMPUTER, "按下按键 " + target,
                    "即将在当前焦点窗口按键 " + target + "，请勿同时操作键盘。")) {
                return DENIED;
            }
            try {
                synchronized (ComputerTools.this) {
                    Robot r = robot();
                    for (int m : parsed) {
                        r.keyPress(m);
                    }
                    Thread.sleep(60);
                    for (int i = parsed.length - 1; i >= 0; i--) {
                        r.keyRelease(parsed[i]);
                    }
                    r.waitForIdle();
                }
                return "已按下「" + target + "」。如需查看按键后的屏幕状态，请调用 computer_screenshot。";
            } catch (Exception e) {
                return "按键「" + target + "」失败：" + msg(e);
            }
        }

        @Tool(name = "computer_type", description = "Type text into the currently focused input by copying it "
                + "to the system clipboard and pressing ctrl+v. Works with Chinese and long text. The user's "
                + "clipboard content WILL BE OVERWRITTEN. Requires user approval; focus the target input first "
                + "with computer_click.")
        public String computerType(
                @ToolParam(name = "text", description = "Text to type into the focused input") String text) {
            String content = text == null ? "" : text;
            if (content.isEmpty()) {
                return "输入失败：text 不能为空。";
            }
            String preview = content.length() > 80 ? content.substring(0, 80) + "…" : content;
            if (!sink.request(OperationGrant.OP_COMPUTER, "向当前焦点输入文本",
                    "将通过剪贴板粘贴输入「" + preview + "」。注意：会覆盖系统剪贴板；请在目标输入框就位后放行。")) {
                return DENIED;
            }
            try {
                synchronized (ComputerTools.this) {
                    Robot r = robot();
                    StringSelection sel = new StringSelection(content);
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
                    r.keyPress(KeyEvent.VK_CONTROL);
                    r.keyPress(KeyEvent.VK_V);
                    Thread.sleep(60);
                    r.keyRelease(KeyEvent.VK_V);
                    r.keyRelease(KeyEvent.VK_CONTROL);
                    r.waitForIdle();
                }
                return "已向当前焦点位置粘贴输入（长度 " + content.length() + " 字符）。请用 computer_screenshot 核对内容是否完整、"
                        + "是否落在了正确的输入框。";
            } catch (Exception e) {
                return "输入失败：" + msg(e);
            }
        }

        /** 解析按键组合为 KeyEvent 码序列（修饰键在前，主键最后；纯修饰键也允许）；不认识返回 null */
        private int[] parseCombo(String combo) {
            String[] parts = combo.split("\\+");
            int[] keys = new int[parts.length];
            int n = 0;
            Integer main = null;
            for (String raw : parts) {
                String p = raw.trim();
                if (p.isEmpty()) {
                    return null;
                }
                Integer code = keyCodeOf(p);
                if (code == null) {
                    return null;
                }
                boolean modifier = p.equals("ctrl") || p.equals("control") || p.equals("alt")
                        || p.equals("shift") || p.equals("win") || p.equals("meta") || p.equals("windows")
                        || p.equals("cmd");
                if (modifier) {
                    keys[n++] = code;
                } else {
                    if (main != null) {
                        return null;
                    }
                    main = code;
                }
            }
            if (main != null) {
                keys[n++] = main;
            }
            int[] out = new int[n];
            System.arraycopy(keys, 0, out, 0, n);
            return out;
        }

        private Integer keyCodeOf(String key) {
            switch (key) {
                case "ctrl":
                case "control":
                    return KeyEvent.VK_CONTROL;
                case "alt":
                    return KeyEvent.VK_ALT;
                case "shift":
                    return KeyEvent.VK_SHIFT;
                case "win":
                case "meta":
                case "windows":
                case "cmd":
                    return KeyEvent.VK_META;
                case "enter":
                case "return":
                    return KeyEvent.VK_ENTER;
                case "esc":
                case "escape":
                    return KeyEvent.VK_ESCAPE;
                case "tab":
                    return KeyEvent.VK_TAB;
                case "space":
                    return KeyEvent.VK_SPACE;
                case "backspace":
                    return KeyEvent.VK_BACK_SPACE;
                case "delete":
                case "del":
                    return KeyEvent.VK_DELETE;
                case "home":
                    return KeyEvent.VK_HOME;
                case "end":
                    return KeyEvent.VK_END;
                case "pageup":
                    return KeyEvent.VK_PAGE_UP;
                case "pagedown":
                    return KeyEvent.VK_PAGE_DOWN;
                case "up":
                    return KeyEvent.VK_UP;
                case "down":
                    return KeyEvent.VK_DOWN;
                case "left":
                    return KeyEvent.VK_LEFT;
                case "right":
                    return KeyEvent.VK_RIGHT;
                case "-":
                    return KeyEvent.VK_MINUS;
                case "=":
                    return KeyEvent.VK_EQUALS;
                case "[":
                    return KeyEvent.VK_OPEN_BRACKET;
                case "]":
                    return KeyEvent.VK_CLOSE_BRACKET;
                case "\\":
                    return KeyEvent.VK_BACK_SLASH;
                case ";":
                    return KeyEvent.VK_SEMICOLON;
                case "'":
                    return KeyEvent.VK_QUOTE;
                case ",":
                    return KeyEvent.VK_COMMA;
                case ".":
                    return KeyEvent.VK_PERIOD;
                case "/":
                    return KeyEvent.VK_SLASH;
                case "`":
                    return KeyEvent.VK_BACK_QUOTE;
                default:
                    break;
            }
            if (key.length() == 1) {
                char c = key.charAt(0);
                if (c >= 'a' && c <= 'z') {
                    return KeyEvent.VK_A + (c - 'a');
                }
                if (c >= '0' && c <= '9') {
                    return KeyEvent.VK_0 + (c - '0');
                }
                return null;
            }
            if (key.length() >= 2 && key.charAt(0) == 'f') {
                try {
                    int fn = Integer.parseInt(key.substring(1));
                    if (fn >= 1 && fn <= 12) {
                        return KeyEvent.VK_F1 + (fn - 1);
                    }
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            return null;
        }
    }

    /** 一次截图：PNG 字节 + 实际回传分辨率（降采样后） */
    private record Shot(byte[] png, int width, int height) {
    }

    /** 全屏截图：原始分辨率版缓存进 lastOriginal（zoom/核对图数据源），回传降采样图并把参照系切回全屏图 */
    private Shot shotPng() throws Exception {
        synchronized (this) {
            BufferedImage original = freshOriginal();
            BufferedImage thumb = downscale(original);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(thumb, "png", bos);
            return new Shot(bos.toByteArray(), thumb.getWidth(), thumb.getHeight());
        }
    }

    /** 重截全屏原始图并更新 lastOriginal 与全屏参照系（须在 synchronize 内调用）；返回原始版供裁剪 */
    private BufferedImage freshOriginal() throws Exception {
        Robot r = robot();
        java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        BufferedImage original = r.createScreenCapture(new Rectangle(screen));
        lastOriginal = original;
        BufferedImage thumb = downscale(original);
        ref = new RefFrame(false, thumb.getWidth(), thumb.getHeight(), 0, 0);
        return original;
    }

    /** 以 (cx, cy) 为中心从原始截图裁边长 side 的局部（自动收敛到屏内），供点击核对图 */
    private static BufferedImage cropAround(BufferedImage src, int cx, int cy, int side) {
        int w = Math.min(side, src.getWidth());
        int h = Math.min(side, src.getHeight());
        int ox = clamp(cx - w / 2, 0, src.getWidth() - w);
        int oy = clamp(cy - h / 2, 0, src.getHeight() - h);
        return src.getSubimage(ox, oy, w, h);
    }

    /** 与 downscale 同公式只算尺寸：把真实屏幕坐标映射到全屏缩略图坐标时用 */
    private static int[] thumbDims(int w, int h) {
        double byEdge = (double) SHOT_MAX_EDGE / Math.max(w, h);
        double byArea = Math.sqrt(SHOT_MAX_PIXELS / ((double) w * h));
        double scale = Math.min(1.0, Math.min(byEdge, byArea));
        return new int[]{Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale))};
    }

    private static byte[] toPng(BufferedImage img) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    /** 降采样到长边 ≤1568 且总像素 ≤115 万（取更严者）：低于各家视觉 API 的静默重采样阈值，保证模型所见即所报 */
    private static BufferedImage downscale(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        double byEdge = (double) SHOT_MAX_EDGE / Math.max(w, h);
        double byArea = Math.sqrt(SHOT_MAX_PIXELS / ((double) w * h));
        double scale = Math.min(1.0, Math.min(byEdge, byArea));
        if (scale >= 1.0) {
            return src;
        }
        int tw = Math.max(1, (int) (w * scale));
        int th = Math.max(1, (int) (h * scale));
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();
        return out;
    }

    /** 截图落盘到工作区 computer/ 目录（可 view_image 重看，即模型所见图）；失败不阻断返回 null */
    private Path saveShot(byte[] png) {
        try {
            Path dir = Paths.get(fileTools.primaryRoot(), "computer");
            Files.createDirectories(dir);
            Path file = dir.resolve("shot_" + LocalDateTime.now().format(TS) + ".png");
            Files.write(file, png);
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    private Robot robot() throws AWTException {
        Robot r = robot;
        if (r == null) {
            synchronized (this) {
                if (robot == null) {
                    if (GraphicsEnvironment.isHeadless()) {
                        throw new IllegalStateException("当前运行环境不支持键鼠操控（headless）");
                    }
                    robot = new Robot();
                    robot.setAutoDelay(40);
                }
                r = robot;
            }
        }
        return r;
    }

    private static ImageBlock imageBlock(byte[] png) {
        return ImageBlock.builder()
                .source(Base64Source.builder()
                        .mediaType("image/png")
                        .data(Base64.getEncoder().encodeToString(png))
                        .build())
                .build();
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
