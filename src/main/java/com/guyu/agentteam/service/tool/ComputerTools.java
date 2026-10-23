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
 * 截图在本地统一降采样（长边≤1568 且总像素≤115 万，取更严者）后回传并如实报告分辨率——低于各家视觉 API
 * 的静默重采样阈值，任何模型所见即所报；点击坐标按「真实屏幕/截图」比例自动换算，与供应商无关。
 * computer_screenshot 只读不审批；点击/按键/打字均受控操作（审批卡，人不在即拒绝）。
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

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final WorkspaceFileTools fileTools;

    /** 懒加载单例：Robot 依赖非 headless 环境；synchronize 串行化避免多方同时抢键鼠 */
    private volatile Robot robot;

    /** 最近一次回传给模型的截图尺寸 {w, h}；点击坐标据此换算到真实屏幕 */
    private volatile int[] lastShot;

    public ComputerTools(WorkspaceFileTools fileTools) {
        this.fileTools = fileTools;
    }

    /** 拼进智能体 system prompt 的键鼠工具使用说明（单聊/成员、编排者、被委派成员三个入口共用） */
    public String promptNote() {
        StringBuilder sb = new StringBuilder("本机键鼠工具说明：computer_* 系列直接操控这台电脑的键盘鼠标，")
                .append("用于浏览器工具覆盖不到的场景（桌面应用、浏览器网页里点不动的按钮、系统弹窗等）。")
                .append("流程：先 computer_screenshot 看当前屏幕、在图上估算目标元素的像素坐标，再 computer_click；")
                .append("点击后会自动回带新截图，据其核对结果或取下一步坐标。")
                .append("坐标一律按「最近一次截图」上的像素直接给出（截图可能被降采样，返回里标注了实际分辨率），")
                .append("后端会自动换算到真实屏幕，切勿自行放大换算坐标。")
                .append("computer_key 传组合键（如 ctrl+s、alt+tab、enter）；computer_type 输入中文/长文本")
                .append("（走剪贴板粘贴，会覆盖系统剪贴板）。注意：你的操作会抢占用户的键盘鼠标焦点，")
                .append("用户此刻可能正在打字——每次点击/按键/打字都要先经用户审批，一次只做一小步，")
                .append("做一步看一张截图确认效果，不要盲操作连点。能网抓/读网页就不动键鼠。");
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
                + "action: estimate the pixel coordinates of your target element on this image. The image may "
                + "be downscaled; its exact resolution is stated in the reply and click coordinates are "
                + "rescaled automatically.")
        public ToolResultBlock computerScreenshot() {
            try {
                Shot shot = shotPng();
                Path file = saveShot(shot.png());
                TextBlock note = TextBlock.builder()
                        .text("当前屏幕截图（分辨率 " + shot.width() + "×" + shot.height()
                                + "，点击坐标按此图上的像素估算即可，后端自动换算到真实屏幕）"
                                + (file != null ? "，已存：" + file : "") + "。请直接描述或分析画面。")
                        .build();
                return ToolResultBlock.of(List.of(note, imageBlock(shot.png())));
            } catch (Exception e) {
                return ToolResultBlock.error("截屏失败：" + msg(e));
            }
        }

        @Tool(name = "computer_click", description = "Click at pixel (x, y) taken from the most recent "
                + "computer_screenshot (the backend rescales screenshot pixels to the physical screen "
                + "automatically). Coordinates MUST come from the latest screenshot. Requires user approval; "
                + "a fresh screenshot is returned afterwards so you can verify the result.")
        public ToolResultBlock computerClick(
                @ToolParam(name = "x", description = "Pixel x on the latest screenshot") int x,
                @ToolParam(name = "y", description = "Pixel y on the latest screenshot") int y,
                @ToolParam(name = "doubleClick", required = false,
                        description = "true for double-click; omit for single click")
                Boolean doubleClick) {
            boolean dbl = Boolean.TRUE.equals(doubleClick);
            String desc = (dbl ? "双击" : "单击") + "屏幕坐标 (" + x + ", " + y + ")";
            if (!sink.request(OperationGrant.OP_COMPUTER, desc,
                    "即将移动并点击鼠标，期间请勿操作键鼠；坐标基于最近一次屏幕截图估算。")) {
                return ToolResultBlock.error(DENIED);
            }
            java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            int[] src = lastShot;
            double sx = (src != null && src[0] > 0) ? (double) screen.width / src[0] : 1.0;
            double sy = (src != null && src[1] > 0) ? (double) screen.height / src[1] : 1.0;
            int cx = Math.max(0, Math.min((int) Math.round(x * sx), screen.width - 1));
            int cy = Math.max(0, Math.min((int) Math.round(y * sy), screen.height - 1));
            try {
                synchronized (ComputerTools.this) {
                    Robot r = robot();
                    r.mouseMove(cx, cy);
                    r.waitForIdle();
                    r.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                    if (dbl) {
                        r.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                        r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                    }
                    r.waitForIdle();
                }
                Shot shot = shotPng();
                Path file = saveShot(shot.png());
                TextBlock note = TextBlock.builder()
                        .text("已" + (dbl ? "双击" : "单击") + "屏幕：截图坐标 (" + x + ", " + y + ") → 真实屏幕 (" + cx + ", " + cy
                                + ")。操作后的最新屏幕截图（分辨率 " + shot.width() + "×" + shot.height()
                                + (file != null ? "，已存：" + file : "")
                                + "）：请核对点击是否生效，目标元素状态是否符合预期。")
                        .build();
                return ToolResultBlock.of(List.of(note, imageBlock(shot.png())));
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

    /** 全屏截图并按上限降采样；坐标空间：模型给截图像素，Robot 用换算后的设备像素 */
    private Shot shotPng() throws Exception {
        synchronized (this) {
            Robot r = robot();
            java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            BufferedImage img = downscale(r.createScreenCapture(new Rectangle(screen)));
            lastShot = new int[]{img.getWidth(), img.getHeight()};
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return new Shot(bos.toByteArray(), img.getWidth(), img.getHeight());
        }
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
