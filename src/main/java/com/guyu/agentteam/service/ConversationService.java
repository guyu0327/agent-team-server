package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.Ids;
import com.guyu.agentteam.dto.AddMembersRequest;
import com.guyu.agentteam.dto.ConversationDto;
import com.guyu.agentteam.dto.GroupChatRequest;
import com.guyu.agentteam.dto.MessageDto;
import com.guyu.agentteam.dto.MessagePageDto;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationMember;
import com.guyu.agentteam.entity.ConversationMemberId;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.repository.ConversationFileGrantRepository;
import com.guyu.agentteam.repository.ConversationMemberRepository;
import com.guyu.agentteam.repository.ConversationRepository;
import com.guyu.agentteam.repository.MessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final MessageRepository messages;
    private final AgentRepository agents;
    private final ConversationFileGrantRepository fileGrants;

    public ConversationService(ConversationRepository conversations, ConversationMemberRepository members,
                               MessageRepository messages, AgentRepository agents,
                               ConversationFileGrantRepository fileGrants) {
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.agents = agents;
        this.fileGrants = fileGrants;
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> list(String userId) {
        return conversations.findByUserIdOrderByPinnedDescLastMessageAtDesc(userId).stream()
                .map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Conversation getEntity(String id) {
        return conversations.findById(id).orElseThrow(() -> ApiException.notFound("会话不存在"));
    }

    @Transactional
    public ConversationDto getOrCreateSingle(String userId, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw ApiException.badRequest("缺少 agentId");
        }
        agents.findById(agentId).orElseThrow(() -> ApiException.notFound("智能体不存在"));
        for (Conversation c : conversations.findByUserIdAndType(userId, "single")) {
            if (memberIds(c.getId()).contains(agentId)) {
                return toDto(c);
            }
        }
        long now = System.currentTimeMillis();
        Conversation c = baseConversation(userId, now);
        c.setType("single");
        conversations.save(c);
        members.save(new ConversationMember(c.getId(), agentId, now));
        return toDto(c);
    }

    @Transactional
    public ConversationDto createGroup(String userId, GroupChatRequest req) {
        if (req == null || req.memberIds() == null || req.memberIds().isEmpty()) {
            throw ApiException.badRequest("群聊成员不能为空");
        }
        for (String agentId : req.memberIds()) {
            agents.findById(agentId).orElseThrow(() -> ApiException.badRequest("智能体不存在: " + agentId));
        }
        long now = System.currentTimeMillis();
        Conversation c = baseConversation(userId, now);
        c.setType("group");
        c.setName(req.name() == null ? "" : req.name().trim());
        c.setChatMode(validateMode(req.chatMode()));
        conversations.save(c);
        for (String agentId : req.memberIds().stream().distinct().toList()) {
            members.save(new ConversationMember(c.getId(), agentId, now));
        }
        return toDto(c);
    }

    @Transactional
    public void rename(String id, String name) {
        Conversation c = getEntity(id);
        c.setName(name == null ? "" : name.trim());
        c.setUpdatedAt(System.currentTimeMillis());
        conversations.save(c);
    }

    @Transactional
    public void setPinned(String id, boolean pinned) {
        Conversation c = getEntity(id);
        c.setPinned(pinned);
        c.setUpdatedAt(System.currentTimeMillis());
        conversations.save(c);
    }

    @Transactional
    public void setMode(String id, String mode) {
        Conversation c = getEntity(id);
        if (!"group".equals(c.getType())) {
            throw ApiException.badRequest("仅群聊支持设置聊天模式");
        }
        c.setChatMode(validateMode(mode));
        c.setUpdatedAt(System.currentTimeMillis());
        conversations.save(c);
    }

    /** 聊天模式取值校验：null 视为默认 passive */
    private String validateMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "passive";
        }
        if (!"passive".equals(mode) && !"free".equals(mode)) {
            throw ApiException.badRequest("未知的聊天模式：" + mode);
        }
        return mode;
    }

    @Transactional
    public ConversationDto markRead(String id) {
        Conversation c = getEntity(id);
        c.setLastReadAt(System.currentTimeMillis());
        conversations.save(c);
        return toDto(c);
    }

    @Transactional
    public ConversationDto addMembers(String id, AddMembersRequest req) {
        Conversation c = getEntity(id);
        long now = System.currentTimeMillis();
        List<String> agentIds = req == null || req.agentIds() == null ? List.of() : req.agentIds();
        for (String agentId : agentIds) {
            agents.findById(agentId).orElseThrow(() -> ApiException.badRequest("智能体不存在: " + agentId));
            if (members.findById(new ConversationMemberId(id, agentId)).isEmpty()) {
                members.save(new ConversationMember(id, agentId, now));
            }
        }
        c.setUpdatedAt(now);
        conversations.save(c);
        return toDto(c);
    }

    /**
     * @return deleted=true 表示群成员已清空，会话随之删除
     */
    @Transactional
    public Map<String, Object> removeMember(String id, String agentId) {
        Conversation c = getEntity(id);
        members.findById(new ConversationMemberId(id, agentId)).ifPresent(members::delete);
        c.setUpdatedAt(System.currentTimeMillis());
        conversations.save(c);
        if (memberIds(id).isEmpty()) {
            deleteAll(c);
            return Map.of("deleted", true);
        }
        return Map.of("deleted", false);
    }

    @Transactional
    public void delete(String id) {
        deleteAll(getEntity(id));
    }

    @Transactional
    public void reset(String id) {
        Conversation c = getEntity(id);
        messages.deleteByConversationId(id);
        fileGrants.deleteByConversationId(id);
        long now = System.currentTimeMillis();
        c.setLastMessage("");
        c.setLastMessageAt(null);
        c.setLastReadAt(now);
        c.setUpdatedAt(now);
        conversations.save(c);
    }

    @Transactional(readOnly = true)
    public MessagePageDto page(String id, Long before, int limit) {
        List<Message> found = before == null
                ? messages.findByConversationIdOrderByCreatedAtDesc(id, PageRequest.of(0, limit))
                : messages.findByConversationIdAndCreatedAtLessThanOrderByCreatedAtDesc(id, before, PageRequest.of(0, limit));
        boolean hasMore = found.size() >= limit;
        List<MessageDto> list = new ArrayList<>(found.stream().map(MessageDto::from).toList());
        Collections.reverse(list);
        return new MessagePageDto(list, hasMore);
    }

    public ConversationDto toDto(Conversation c) {
        List<String> ids = memberIds(c.getId());
        String agentId = "single".equals(c.getType()) && !ids.isEmpty() ? ids.get(0) : null;
        long lastRead = c.getLastReadAt() == null ? 0L : c.getLastReadAt();
        long unread = messages.countByConversationIdAndCreatedAtGreaterThanAndSenderTypeNot(c.getId(), lastRead, "user");
        return new ConversationDto(c.getId(), c.getType(), c.getName() == null ? "" : c.getName(), agentId,
                ids, c.getChatMode() == null ? "passive" : c.getChatMode(), c.isPinned(),
                c.getLastMessage() == null ? "" : c.getLastMessage(),
                c.getLastMessageAt(), unread);
    }

    private Conversation baseConversation(String userId, long now) {
        Conversation c = new Conversation();
        c.setId(Ids.next());
        c.setUserId(userId);
        c.setType("single");
        c.setName("");
        c.setChatMode("passive");
        c.setPinned(false);
        c.setLastMessage("");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    private List<String> memberIds(String conversationId) {
        return members.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(ConversationMember::getAgentId).toList();
    }

    private void deleteAll(Conversation c) {
        messages.deleteByConversationId(c.getId());
        members.deleteByConversationId(c.getId());
        fileGrants.deleteByConversationId(c.getId());
        conversations.delete(c);
    }
}
