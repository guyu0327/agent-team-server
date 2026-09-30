package com.guyu.agentteam.service.orchestration;

import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.ModelPreset;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 用智能体关联的模型预设构建 AgentScope 的 OpenAI 兼容模型 */
@Component
public class AgentModelFactory {

    /**
     * 框架传输层默认 readTimeout/responseTimeout 均仅 5 分钟，会把单次流式模型调用
     * （长输出/慢供应商）在 300 秒掐断，与协作成员轮上限（120 分钟）不匹配。
     * 两项放开到同量级作为保险（此前 300 秒中止的主因是工具执行层的 TOOL_DEFAULTS，
     * 在各 ReActAgent 构建处以 toolExecutionConfig 放开）；流中途静默仍按框架
     * 默认 5 分钟判死（真死连接不该久等）。
     */
    private static final HttpTransport MODEL_TRANSPORT = JdkHttpTransport.builder()
            .config(HttpTransportConfig.builder()
                    .responseTimeout(Duration.ofMinutes(120))
                    .readTimeout(Duration.ofMinutes(120))
                    .build())
            .build();

    public Model create(Agent agent, ModelPreset preset) {
        return create(agent, preset, false);
    }

    /**
     * multiAgent=true 时改用框架的 OpenAIMultiAgentFormatter：
     * 历史里携带名称的用户/成员消息会被合并成带署名的 &lt;history&gt; 用户消息，
     * 群聊成员因此能分清「谁说了什么」，而不是把别人的发言当成自己的历史。
     */
    public Model create(Agent agent, ModelPreset preset, boolean multiAgent) {
        GenerateOptions.Builder options = GenerateOptions.builder();
        if (agent.getTemperature() != null) {
            options.temperature(agent.getTemperature());
        }
        return OpenAIChatModel.builder()
                .apiKey(preset.getApiKey())
                .baseUrl(preset.getBaseUrl())
                .modelName(preset.getName())
                .stream(true)
                .formatter(multiAgent ? new OpenAIMultiAgentFormatter() : new OpenAIChatFormatter())
                .generateOptions(options.build())
                .httpTransport(MODEL_TRANSPORT)
                .build();
    }
}
