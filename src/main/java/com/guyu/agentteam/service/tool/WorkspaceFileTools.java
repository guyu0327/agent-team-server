package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.entity.AppSetting;
import com.guyu.agentteam.entity.ConversationFileGrant;
import com.guyu.agentteam.repository.AppSettingRepository;
import com.guyu.agentteam.repository.ConversationFileGrantRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 智能体文件工具：路径沙箱 = 主工作区目录 + 白名单目录 + 当前会话的文件授权，
 * 前两者通过设置 API 运行时可改，会话授权来自聊天中附加的文件/文件夹。
 * 规则：相对路径解析到主工作区；绝对路径必须落在允许范围内。
 * 越界/非法路径返回错误说明而非抛异常，让模型能自行纠正。
 * 注册给 Toolkit 时使用 {@link #scoped(String)} 返回的会话作用域对象，工具实现按会话合并授权目录。
 */
@Service
public class WorkspaceFileTools {

    /** read_file 单次返回的最大字符数，避免撑爆模型上下文 */
    private static final int MAX_READ_CHARS = 100_000;

    private static final Logger log = LoggerFactory.getLogger(WorkspaceFileTools.class);

    public static final String KEY_ROOT = "workspace.root";
    public static final String KEY_EXTRA_DIRS = "workspace.extraDirs";

    private final AppSettingRepository settings;
    private final ConversationFileGrantRepository grants;
    /** 未做过任何设置时使用的主工作区目录（来自 yaml） */
    private final Path defaultRoot;

    /** 允许读写的根目录，第一个是主工作区，其余是白名单目录 */
    private volatile List<Path> allowedRoots = List.of();

    public WorkspaceFileTools(AppSettingRepository settings,
                              ConversationFileGrantRepository grants,
                              @Value("${app.workspace.root:./workspace}") String defaultRootDir) {
        this.settings = settings;
        this.grants = grants;
        this.defaultRoot = Paths.get(defaultRootDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void load() {
        String savedRoot = readSetting(KEY_ROOT);
        List<Path> roots = new ArrayList<>();
        roots.add(savedRoot == null ? defaultRoot : Paths.get(savedRoot).toAbsolutePath().normalize());
        String savedExtra = readSetting(KEY_EXTRA_DIRS);
        if (savedExtra != null) {
            for (String line : savedExtra.split("\n")) {
                if (!line.isBlank()) {
                    roots.add(Paths.get(line.trim()).toAbsolutePath().normalize());
                }
            }
        }
        try {
            ensureDirs(roots);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建工作区目录：" + e.getMessage(), e);
        }
        allowedRoots = List.copyOf(roots);
    }

    /** 校验并应用新的沙箱范围：主目录可相对（相对进程工作目录），白名单必须绝对路径 */
    public synchronized void updateSandbox(String rootDir, List<String> extraDirs) {
        if (rootDir == null || rootDir.isBlank()) {
            throw ApiException.badRequest("主工作区目录不能为空");
        }
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(Paths.get(rootDir.trim()).toAbsolutePath().normalize());
        for (String raw : extraDirs == null ? List.<String>of() : extraDirs) {
            String dir = raw == null ? "" : raw.trim();
            if (dir.isEmpty()) continue;
            Path p = Paths.get(dir);
            if (!p.isAbsolute()) {
                throw ApiException.badRequest("白名单目录必须是绝对路径：" + dir);
            }
            roots.add(p.normalize());
        }
        List<Path> list = new ArrayList<>(roots);
        try {
            ensureDirs(list);
        } catch (IOException e) {
            throw ApiException.badRequest("无法创建目录：" + e.getMessage());
        }
        saveSetting(KEY_ROOT, list.get(0).toString());
        saveSetting(KEY_EXTRA_DIRS, String.join("\n", list.stream().skip(1).map(Path::toString).toList()));
        allowedRoots = List.copyOf(list);
    }

    private void ensureDirs(List<Path> roots) throws IOException {
        for (Path p : roots) {
            Files.createDirectories(p);
        }
    }

    private String readSetting(String key) {
        return settings.findById(key).map(AppSetting::getSettingValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(null);
    }

    private void saveSetting(String key, String value) {
        AppSetting s = settings.findById(key).orElseGet(() -> {
            AppSetting n = new AppSetting();
            n.setSettingKey(key);
            return n;
        });
        s.setSettingValue(value);
        s.setUpdatedAt(System.currentTimeMillis());
        settings.save(s);
    }

    /** 当前生效的主工作区目录（绝对路径） */
    public String primaryRoot() {
        return allowedRoots.get(0).toString();
    }

    /** 当前生效的白名单目录（绝对路径） */
    public List<String> extraRoots() {
        return allowedRoots.stream().skip(1).map(Path::toString).toList();
    }

    /** 全局沙箱 + 会话授权合并后的允许根目录 */
    public List<Path> allowedRootsFor(String conversationId) {
        List<Path> roots = new ArrayList<>(allowedRoots);
        for (ConversationFileGrant g : conversationGrants(conversationId)) {
            try {
                roots.add(Paths.get(g.getPath()).normalize());
            } catch (Exception ignored) {
                // 授权路径非法时忽略，不影响其他目录
            }
        }
        return roots;
    }

    private List<ConversationFileGrant> conversationGrants(String conversationId) {
        return conversationId == null || conversationId.isBlank()
                ? List.of()
                : grants.findByConversationIdOrderByGrantedAtAsc(conversationId);
    }

    /** 拼进智能体 system prompt 的文件工具使用说明（含本会话授权的文件/目录） */
    public String promptNote(String conversationId) {
        StringBuilder sb = new StringBuilder("文件工具说明：你可以使用 write_file / read_file / list_dir 操作文本文件。")
                .append("相对路径相对于主工作区根目录 ").append(primaryRoot())
                .append("；绝对路径允许当且仅当位于主工作区或白名单目录");
        List<String> extras = extraRoots();
        if (extras.isEmpty()) {
            sb.append("。");
        } else {
            sb.append("：").append(String.join("、", extras)).append("。");
        }
        List<ConversationFileGrant> mine = conversationGrants(conversationId);
        if (!mine.isEmpty()) {
            sb.append("用户已为本会话授权下列文件/目录（通常是用户附加的资料或任务目标），你可以直接读写：\n");
            for (ConversationFileGrant g : mine) {
                sb.append("- ").append(ConversationFileGrant.TYPE_DIR.equals(g.getType()) ? "[文件夹] " : "[文件] ")
                        .append(g.getPath()).append("\n");
            }
        }
        sb.append("如果任务要求把成果写到文件，必须实际调用 write_file 完成写入，不要只在回复里贴出内容。")
                .append("文件操作没有记忆或缓存：即使之前写过同一文件，每一次都必须当轮重新调用工具，")
                .append("并在收到工具返回的「已写入」回执后才能告知用户成功；未调用工具就宣称已写入是严重错误。");
        return sb.toString();
    }

    /** 注册给 Toolkit 的会话作用域工具对象 */
    public ScopedTools scoped(String conversationId) {
        return new ScopedTools(() -> conversationId);
    }

    /** 作用域动态解析：每次工具调用时取当前协作目标会话（编排建群后切到项目群，群内撤销立即生效） */
    public ScopedTools scoped(Supplier<String> conversationId) {
        return new ScopedTools(conversationId);
    }

    /** 会话作用域的文件工具：所有读写都合并该会话用户授权的路径 */
    public class ScopedTools {

        private final Supplier<String> conversationId;

        public ScopedTools(Supplier<String> conversationId) {
            this.conversationId = conversationId;
        }

        @Tool(name = "write_file", description = "把文本内容写入沙箱内的文件（整文件覆盖），父目录不存在时自动创建")
        public String writeFile(
                @ToolParam(name = "path", required = true, description = "文件路径：相对主工作区根目录（如 demo/hello.py），或允许范围内的绝对路径") String path,
                @ToolParam(name = "content", required = true, description = "要写入的完整文本内容") String content) {
            return doWriteFile(path, content, conversationId.get());
        }

        @Tool(name = "read_file", description = "读取沙箱内文本文件的内容")
        public String readFile(
                @ToolParam(name = "path", required = true, description = "文件路径：相对主工作区根目录，或允许范围内的绝对路径") String path) {
            return doReadFile(path, conversationId.get());
        }

        @Tool(name = "list_dir", description = "列出沙箱内某个目录下的文件和子目录")
        public String listDir(
                @ToolParam(name = "path", required = false, description = "目录路径：相对主工作区根目录，缺省为主工作区根目录") String path) {
            return doListDir(path, conversationId.get());
        }
    }

    private String doWriteFile(String path, String content, String conversationId) {
        Path p = resolve(path, conversationId);
        if (p == null) {
            log.info("write_file REJECTED path={}", path);
            return pathErr(path);
        }
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8);
            log.info("write_file OK path={} chars={}", p, content == null ? 0 : content.length());
            return "已写入 " + p + "（" + (content == null ? 0 : content.length()) + " 字符）";
        } catch (IOException e) {
            log.info("write_file FAIL path={} err={}", p, e.getMessage());
            return "写入失败：" + e.getMessage();
        }
    }

    private String doReadFile(String path, String conversationId) {
        Path p = resolve(path, conversationId);
        if (p == null) {
            log.info("read_file REJECTED path={}", path);
            return pathErr(path);
        }
        if (!Files.isRegularFile(p)) return "文件不存在或不是普通文件：" + path;
        try {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            if (text.isEmpty()) return "（文件为空）";
            if (text.length() > MAX_READ_CHARS) {
                return text.substring(0, MAX_READ_CHARS)
                        + "\n\n（内容过长已截断，完整共 " + text.length() + " 字符）";
            }
            return text;
        } catch (IOException e) {
            return "读取失败：" + e.getMessage();
        }
    }

    private String doListDir(String path, String conversationId) {
        String raw = path == null || path.isBlank() ? "." : path;
        Path p = resolve(raw, conversationId);
        if (p == null) return pathErr(raw);
        if (!Files.isDirectory(p)) return "目录不存在或不是目录：" + raw;
        try (Stream<Path> s = Files.list(p)) {
            List<String> items = s.sorted(Comparator.comparing((Path x) -> !Files.isDirectory(x))
                            .thenComparing(x -> x.getFileName().toString()))
                    .map(x -> Files.isDirectory(x)
                            ? "[目录] " + x.getFileName()
                            : x.getFileName() + "（" + sizeOf(x) + "）")
                    .toList();
            if (items.isEmpty()) return "（空目录）";
            return "目录「" + raw.trim() + "」共 " + items.size() + " 项：\n" + String.join("\n", items);
        } catch (IOException e) {
            return "列目录失败：" + e.getMessage();
        }
    }

    private String sizeOf(Path p) {
        try {
            long n = Files.size(p);
            return n < 1024 ? n + " B" : String.format("%.1f KB", n / 1024.0);
        } catch (IOException e) {
            return "?";
        }
    }

    /** 校验并解析路径；空白、非法或越界返回 null */
    private Path resolve(String path, String conversationId) {
        if (path == null || path.isBlank()) return null;
        Path p;
        try {
            p = allowedRoots.get(0).resolve(path.trim()).normalize();
        } catch (Exception e) {
            return null;
        }
        for (Path root : allowedRootsFor(conversationId)) {
            if (p.startsWith(root)) return p;
        }
        return null;
    }

    private String pathErr(String path) {
        return "路径无效或超出沙箱范围：" + path + "。相对路径请相对主工作区根目录，绝对路径必须在主工作区、"
                + "白名单目录或用户为本会话授权的文件/目录内";
    }
}
