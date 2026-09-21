package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.entity.OperationGrant;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.tool.ShellExecuteTool;

import java.util.function.Supplier;

/**
 * execute 工具的审批门控包装：每次调用经 {@link OpRequestSink} 弹审批卡片，
 * 阻塞等待用户决定——批准则委托框架执行（传 null RuntimeContext：LocalFilesystemWithShell
 * 构造时已固定 shellCwd 且未设 NamespaceFactory，执行路径不会解引用该参数），拒绝/超时返回引导文本。
 */
public class GatedShellTool {

    /** 未获批准时返回给模型的工具结果 */
    public static final String DENIED =
            "【未获批准】用户没有允许执行这条终端命令，命令未执行。请尊重用户的决定，"
                    + "不要重复提交同样的命令；如确有需要，请在回复中说明该命令的用途，等用户同意后再试。";

    private final ShellExecuteTool delegate;
    private final Supplier<String> conversationId;
    private final OpRequestSink sink;

    public GatedShellTool(ShellExecuteTool delegate, Supplier<String> conversationId, OpRequestSink sink) {
        this.delegate = delegate;
        this.conversationId = conversationId;
        this.sink = sink;
    }

    @Tool(description = "Execute a shell command (cmd on Windows). Use for git, npm, build, test, and other "
            + "terminal operations. The first use in a conversation needs user approval via an approval card; "
            + "once allowed for the session, later commands in the same conversation run without asking again. "
            + "Returns combined output and exit code. If a dedicated tool exists (e.g., "
            + "read_file, write_file), you MUST use it instead of shell commands.")
    public String execute(
            @ToolParam(name = "command", description = "Shell command to execute") String command,
            @ToolParam(name = "working_directory", required = false,
                    description = "Working directory (relative to workspace root, optional)") String workingDirectory,
            @ToolParam(name = "timeout", required = false,
                    description = "Timeout in seconds (default: 30)") Integer timeout) {
        if (!sink.request(OperationGrant.OP_SHELL, null, command)) {
            return DENIED;
        }
        return delegate.execute(null, command, workingDirectory, timeout);
    }
}
