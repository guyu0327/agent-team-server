package com.guyu.agentteam.service.orchestration;

import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.ModelPreset;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import org.springframework.stereotype.Component;

/** 用智能体关联的模型预设构建 AgentScope 的 OpenAI 兼容模型 */
@Component
public class AgentModelFactory {

    public Model create(Agent agent, ModelPreset preset) {
        GenerateOptions.Builder options = GenerateOptions.builder();
        if (agent.getTemperature() != null) {
            options.temperature(agent.getTemperature());
        }
        return OpenAIChatModel.builder()
                .apiKey(preset.getApiKey())
                .baseUrl(preset.getBaseUrl())
                .modelName(preset.getName())
                .stream(true)
                .formatter(new OpenAIChatFormatter())
                .generateOptions(options.build())
                .build();
    }
}
