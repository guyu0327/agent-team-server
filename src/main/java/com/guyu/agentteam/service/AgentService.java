package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.CurrentUser;
import com.guyu.agentteam.common.Ids;
import com.guyu.agentteam.dto.AgentDto;
import com.guyu.agentteam.dto.AgentUpsertRequest;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationMember;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.repository.ConversationMemberRepository;
import com.guyu.agentteam.repository.ConversationRepository;
import com.guyu.agentteam.repository.MessageRepository;
import com.guyu.agentteam.repository.ModelPresetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AgentService {

    private final AgentRepository agents;
    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final MessageRepository messages;
    private final ModelPresetRepository presets;

    public AgentService(AgentRepository agents, ConversationRepository conversations,
                        ConversationMemberRepository members, MessageRepository messages,
                        ModelPresetRepository presets) {
        this.agents = agents;
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.presets = presets;
    }

    @Transactional(readOnly = true)
    public List<AgentDto> list() {
        return agents.findByOrderByCreatedAtAsc().stream().map(AgentDto::from).toList();
    }

    @Transactional
    public AgentDto create(AgentUpsertRequest req) {
        validate(req);
        long now = System.currentTimeMillis();
        Agent a = new Agent();
        a.setId(Ids.next());
        a.setUserId(CurrentUser.ID);
        apply(a, req, true);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        agents.save(a);
        return AgentDto.from(a);
    }

    @Transactional
    public AgentDto update(String id, AgentUpsertRequest req) {
        validate(req);
        Agent a = find(id);
        apply(a, req, false);
        a.setUpdatedAt(System.currentTimeMillis());
        agents.save(a);
        return AgentDto.from(a);
    }

    /**
     * 级联清理：单聊会话整个删除；群聊移除该成员，群空了则删群。
     */
    @Transactional
    public void delete(String id) {
        Agent a = find(id);
        for (ConversationMember m : members.findByAgentId(id)) {
            Conversation conv = conversations.findById(m.getConversationId()).orElse(null);
            if (conv == null) {
                continue;
            }
            if ("single".equals(conv.getType())) {
                deleteConversation(conv);
            } else {
                members.delete(m);
                if (members.findByConversationIdOrderByCreatedAtAsc(conv.getId()).isEmpty()) {
                    deleteConversation(conv);
                }
            }
        }
        agents.delete(a);
    }

    private Agent find(String id) {
        return agents.findById(id).orElseThrow(() -> ApiException.notFound("智能体不存在"));
    }

    private void validate(AgentUpsertRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("智能体名称不能为空");
        }
        if (req.presetId() == null || req.presetId().isBlank()) {
            throw ApiException.badRequest("请选择模型预设");
        }
        if (!presets.existsById(req.presetId())) {
            throw ApiException.badRequest("模型预设不存在");
        }
    }

    private void apply(Agent a, AgentUpsertRequest req, boolean isCreate) {
        a.setName(req.name().trim());
        a.setAvatar(req.avatar() == null ? "" : req.avatar());
        a.setGroupName(req.groupName() == null ? "" : req.groupName().trim());
        a.setDescription(req.description() == null ? "" : req.description());
        a.setPresetId(req.presetId());
        a.setIsOrchestrator(Boolean.TRUE.equals(req.isOrchestrator()));
        a.setSystemPrompt(req.systemPrompt() == null ? "" : req.systemPrompt());
        a.setTemperature(req.temperature() == null ? 0.7 : req.temperature());
    }

    private void deleteConversation(Conversation conv) {
        messages.deleteByConversationId(conv.getId());
        members.deleteByConversationId(conv.getId());
        conversations.delete(conv);
    }
}
