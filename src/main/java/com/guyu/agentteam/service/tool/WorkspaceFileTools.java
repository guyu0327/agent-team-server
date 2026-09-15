package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.entity.AppSetting;
import com.guyu.agentteam.entity.ConversationFileGrant;
import com.guyu.agentteam.entity.OperationGrant;
import com.guyu.agentteam.repository.AppSettingRepository;
import com.guyu.agentteam.repository.ConversationFileGrantRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 智能体文件工具：路径沙箱 = 主工作区目录 + 白名单目录 + 当前会话的文件授权，
 * 前两者通过设置 API 运行时可改，会话授权来自聊天中附加的文件/文件夹。
 * 规则：相对路径解析到主工作区；绝对路径必须落在允许范围内。
 * 读写实现采用 harness 框架的 FilesystemTool（read_file/write_file/edit_file/
 * grep_files/glob_files/list_files），其中 write/edit 与 shell 命令（execute）是
 * 受控操作：调用时经 {@link OpRequestSink} 弹审批卡片等用户决定，拒绝则不执行。
 */
@Service
public class WorkspaceFileTools {

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
        StringBuilder sb = new StringBuilder("文件工具说明：沙箱内可使用 read_file（读取文本，支持 offset/limit 分页）、")
                .append("grep_files（按内容搜索文件）、glob_files（按通配符查找文件）、list_files（列出目录内容）。")
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
        sb.append("write_file（写入/覆盖文本文件）、edit_file（精确替换内容）与 execute（执行 shell 命令，")
                .append("Windows 下为 cmd，working_directory 相对主工作区根目录）是受控操作：")
                .append("调用时会向用户展示操作详情并等待批准（允许一次 / 本会话此类操作允许 / 拒绝），")
                .append("被拒绝或超时未响应的操作不会执行，请勿反复重试同一次调用。")
                .append("普通文件读写请优先使用文件工具。")
                .append("如果任务要求把成果写到文件，必须实际调用 write_file 完成写入，不要只在回复里贴出内容；")
                .append("文件操作没有记忆或缓存：即使之前写过同一文件，每一次都必须当轮重新调用工具，")
                .append("并在收到工具返回的「已写入」回执后才能告知用户成功；未调用工具就宣称已写入是严重错误。");
        return sb.toString();
    }

    /** 会话作用域的框架文件工具：沙箱 = 主工作区 + 白名单 + 本会话授权，write/edit 需审批 */
    public FilesystemTool toolsFor(String conversationId, OpRequestSink sink) {
        return routingTools(() -> allowedRootsFor(conversationId), sink);
    }

    /** 动态作用域（编排者）：每次工具调用解析当前协作目标会话，建群后切目标、群内撤销立即生效 */
    public FilesystemTool toolsFor(Supplier<String> conversationId, OpRequestSink sink) {
        return routingTools(() -> allowedRootsFor(conversationId.get()), sink);
    }

    /** 注册 shell 命令工具（execute）：working_directory 相对主工作区解析，默认 30 秒超时，需审批 */
    public void registerShellTool(Toolkit toolkit, Supplier<String> conversationId, OpRequestSink sink) {
        toolkit.registerTool(new GatedShellTool(
                new ShellExecuteTool(new LocalFilesystemWithShell(allowedRoots.get(0))),
                conversationId, sink));
    }

    private FilesystemTool routingTools(Supplier<List<Path>> rootsSupplier, OpRequestSink sink) {
        AbstractFilesystem routing = (AbstractFilesystem) Proxy.newProxyInstance(
                WorkspaceFileTools.class.getClassLoader(), new Class<?>[]{AbstractFilesystem.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(proxy, args);
                    }
                    Object denied = gateIfControlled(method.getName(), args, sink);
                    if (denied != null) {
                        return denied;
                    }
                    List<Path> roots = rootsSupplier.get();
                    Path target = targetPathOf(args);
                    return method.invoke(filesystemFor(roots, target), args);
                });
        return new FilesystemTool(routing);
    }

    /**
     * write/edit 为受控操作：先经审批（弹卡片阻塞等用户决定），未批准直接返回 fail 结果不落盘。
     * 返回 null 表示非受控操作或已获批准，继续正常调用。
     */
    private Object gateIfControlled(String method, Object[] args, OpRequestSink sink) {
        if (sink == null) {
            return null;
        }
        String opType;
        String target;
        String detail;
        switch (method) {
            case "write" -> {
                opType = OperationGrant.OP_WRITE;
                target = strArg(args, 1);
                detail = strArg(args, 2);
            }
            case "edit" -> {
                opType = OperationGrant.OP_EDIT;
                target = strArg(args, 1);
                detail = "原文：\n" + strArg(args, 2) + "\n改为：\n" + strArg(args, 3);
            }
            default -> {
                return null;
            }
        }
        if (sink.request(opType, target, detail)) {
            return null;
        }
        return OperationGrant.OP_EDIT.equals(opType)
                ? EditResult.fail("用户未批准本次修改，文件未被更改。请尊重用户的决定，不要重复提交同样的修改。")
                : WriteResult.fail("用户未批准本次写入，文件未被写入。请尊重用户的决定，不要重复提交同样的写入。");
    }

    private String strArg(Object[] args, int index) {
        return args != null && args.length > index && args[index] instanceof String s ? s : null;
    }

    /**
     * 按盘符选择文件系统实例：框架的 LocalFilesystem 在 Windows 上跨盘符访问
     * additionalRoots 会因 cwd.relativize 抛「other has different root」（SANDBOXED 模式
     * 则把外部目录当沙箱内相对路径解析而不可达），因此为每个涉及到的盘符各建一个
     * cwd 落在该盘的实例。target 为绝对路径时按其盘符路由；相对路径始终落主工作区。
     */
    private AbstractFilesystem filesystemFor(List<Path> roots, Path target) {
        Path primary = roots.get(0);
        if (target != null && !driveOf(target).equals(driveOf(primary))) {
            List<Path> driveRoots = roots.stream().filter(r -> driveOf(r).equals(driveOf(target))).toList();
            if (!driveRoots.isEmpty()) {
                return buildFilesystem(driveRoots);
            }
        }
        List<Path> sameDrive = roots.stream().skip(1).filter(r -> driveOf(r).equals(driveOf(primary))).toList();
        return buildFilesystem(primary, sameDrive);
    }

    /** buildFilesystem 的单列表入口：第一个元素是 project（cwd），其余是 additionalRoots */
    private AbstractFilesystem buildFilesystem(Path project, List<Path> additional) {
        return new LocalFilesystemSpec()
                .project(project)
                .projectWritable(true)
                .additionalRoots(additional)
                .toFilesystem(project, null);
    }

    private AbstractFilesystem buildFilesystem(List<Path> roots) {
        return buildFilesystem(roots.get(0), roots.subList(1, roots.size()));
    }

    /** 从工具调用参数里找绝对路径（支持模型回传的 /C:/... 前缀形态），找不到返回 null */
    private Path targetPathOf(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (!(a instanceof String s)) continue;
            String t = s.trim();
            if (t.startsWith("/")) t = t.substring(1);
            if (t.length() >= 2 && t.charAt(1) == ':') {
                try {
                    return Paths.get(t);
                } catch (Exception ignored) {
                    // 非法路径参数交给框架按原样处理并报错
                }
            }
        }
        return null;
    }

    /** 路径的盘符标识（Windows 大小写不敏感；非盘符路径归到空串） */
    private String driveOf(Path p) {
        Path root = p.toAbsolutePath().getRoot();
        return root == null ? "" : root.toString().toUpperCase();
    }
}
