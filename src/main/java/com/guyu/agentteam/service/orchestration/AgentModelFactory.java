package com.guyu.agentteam.service.orchestration;

import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.ModelPreset;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;
import org.springframework.stereotype.Component;

/** 用智能体关联的模型预设构建 AgentScope 的 OpenAI 兼容模型 */
@Component
public class AgentModelFactory {

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
                .build();
    }
}
