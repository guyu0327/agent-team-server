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
import com.guyu.agentteam.repository.OperationGrantRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class ConversationService {

    public static final String CATEGORY_CHAT = "chat";
    public static final String CATEGORY_TASK = "task";

    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final MessageRepository messages;
    private final AgentRepository agents;
    private final ConversationFileGrantRepository fileGrants;
    private final OperationGrantRepository operationGrants;
    private final ScheduledTaskService scheduledTasks;

    public ConversationService(ConversationRepository conversations, ConversationMemberRepository members,
                               MessageRepository messages, AgentRepository agents,
                               ConversationFileGrantRepository fileGrants,
                               OperationGrantRepository operationGrants,
                               ScheduledTaskService scheduledTasks) {
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.agents = agents;
        this.fileGrants = fileGrants;
        this.operationGrants = operationGrants;
        this.scheduledTasks = scheduledTasks;
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> list(String userId) {
        return conversations.findByUserIdAndCategoryAndArchivedAtIsNullOrderByPinnedDescLastMessageAtDesc(
                        userId, CATEGORY_CHAT).stream()
                .map(this::toDto).toList();
    }

    /** 历史会话列表：按归档时间倒序；普通会话与定时任务归档（category=task）混排，由前端打标签区分；agentId 非空时只回与其绑定的会话 */
    @Transactional(readOnly = true)
    public List<ConversationDto> listArchived(String userId, String agentId) {
        return conversations.findByUserIdAndArchivedAtIsNotNullOrderByArchivedAtDesc(userId).stream()
                .filter(c -> agentId == null || agentId.isBlank() || memberIds(c.getId()).contains(agentId))
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
        for (Conversation c : conversations.findByUserIdAndTypeAndCategoryAndArchivedAtIsNull(userId, "single", CATEGORY_CHAT)) {
            // 微信通道的单聊属于微信好友会话（桌面只读），不能被桌面「发消息」复用，否则发送被拦
            if ("wechat".equals(c.getChannel())) {
                continue;
            }
            if (memberIds(c.getId()).contains(agentId)) {
                return toDto(c);
            }
        }
        long now = System.currentTimeMillis();
        Conversation c = newConversation(userId, "single", CATEGORY_CHAT, "");
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
        Conversation c = newConversation(userId, "group", CATEGORY_CHAT,
                req.name() == null ? "" : req.name().trim());
        c.setChatMode(validateMode(req.chatMode()));
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

    /** 归档到历史会话：空会话直接物理删除，不产生空历史 */
    @Transactional
    public void archive(String id) {
        Conversation c = getEntity(id);
        if (c.getArchivedAt() != null) {
            return;
        }
        scheduledTasks.cancelAllForConversation(id);
        if (!messages.existsByConversationId(id)) {
            deleteAll(c);
            return;
        }
        archiveInPlace(c);
    }

    /** 恢复历史会话到消息列表。单聊冲突处理：同智能体已有活跃单聊时，有消息的先归档、空的物理删除（swap） */
    @Transactional
    public ConversationDto restore(String id) {
        Conversation c = getEntity(id);
        if (c.getArchivedAt() == null) {
            return toDto(c);
        }
        // 微信归档不可恢复为聊天：会话与微信绑定一一对应，恢复会产生脱离微信的副本
        if ("wechat".equals(c.getChannel())) {
            throw ApiException.badRequest("微信会话不支持恢复聊天，旧记录仅供留档查看");
        }
        if ("single".equals(c.getType())) {
            String agentId = memberIds(id).isEmpty() ? null : memberIds(id).get(0);
            if (agentId != null) {
                for (Conversation other : conversations.findByUserIdAndTypeAndCategoryAndArchivedAtIsNull(
                        c.getUserId(), "single", CATEGORY_CHAT)) {
                    if (!memberIds(other.getId()).contains(agentId)) {
                        continue;
                    }
                    if (messages.existsByConversationId(other.getId())) {
                        archiveInPlace(other);
                    } else {
                        deleteAll(other);
                    }
                    break;
                }
            }
        }
        c.setArchivedAt(null);
        c.setUpdatedAt(System.currentTimeMillis());
        conversations.save(c);
        return toDto(c);
    }

    /** 归档落库：清置顶、归零未读，members/grants 全不动，恢复后直接可用 */
    private void archiveInPlace(Conversation c) {
        long now = System.currentTimeMillis();
        c.setArchivedAt(now);
        c.setPinned(false);
        c.setLastReadAt(now);
        c.setUpdatedAt(now);
        conversations.save(c);
    }

    @Transactional
    public void reset(String id) {
        Conversation c = getEntity(id);
        messages.deleteByConversationId(id);
        fileGrants.deleteByConversationId(id);
        operationGrants.deleteByConversationId(id);
        long now = System.currentTimeMillis();
        c.setLastMessage("");
        c.setLastMessageAt(null);
        c.setLastReadAt(now);
        c.setUpdatedAt(now);
        conversations.save(c);
    }

    /** 重置群聊：旧群归档进历史会话，按原名称、成员与聊天模式重建新群并返回 */
    @Transactional
    public ConversationDto resetGroup(String id) {
        Conversation c = getEntity(id);
        if (!"group".equals(c.getType()) || !CATEGORY_CHAT.equals(c.getCategory())) {
            throw ApiException.badRequest("仅普通群聊支持重置");
        }
        String name = c.getName();
        String chatMode = c.getChatMode();
        List<String> memberIds = memberIds(id);
        archive(id);
        Conversation fresh = newConversation(c.getUserId(), "group", CATEGORY_CHAT, name);
        fresh.setChatMode(validateMode(chatMode));
        long now = System.currentTimeMillis();
        for (String agentId : memberIds) {
            members.save(new ConversationMember(fresh.getId(), agentId, now));
        }
        return toDto(fresh);
    }

    /** 单页上限：请求 size+1 条判断 hasMore，避免满页时多一次空请求 */
    private static final int PAGE_MAX = 200;

    @Transactional(readOnly = true)
    public MessagePageDto page(String id, Long before, int limit, String taskId) {
        int size = Math.max(1, Math.min(limit, PAGE_MAX));
        boolean filtered = taskId != null && !taskId.isBlank();
        List<Message> found = before == null
                ? (filtered
                        ? messages.findByConversationIdAndTaskIdOrderByCreatedAtDesc(id, taskId, PageRequest.of(0, size + 1))
                        : messages.findByConversationIdOrderByCreatedAtDesc(id, PageRequest.of(0, size + 1)))
                : (filtered
                        ? messages.findByConversationIdAndTaskIdAndCreatedAtLessThanOrderByCreatedAtDesc(id, taskId, before, PageRequest.of(0, size + 1))
                        : messages.findByConversationIdAndCreatedAtLessThanOrderByCreatedAtDesc(id, before, PageRequest.of(0, size + 1)));
        boolean hasMore = found.size() > size;
        List<Message> page = hasMore ? found.subList(0, size) : found;
        List<MessageDto> list = new ArrayList<>(page.stream().map(MessageDto::from).toList());
        Collections.reverse(list);
        return new MessagePageDto(list, hasMore);
    }

    public ConversationDto toDto(Conversation c) {
        List<String> ids = memberIds(c.getId());
        String agentId = "single".equals(c.getType()) && !ids.isEmpty() ? ids.get(0) : null;
        long lastRead = c.getLastReadAt() == null ? 0L : c.getLastReadAt();
        long unread = messages.countByConversationIdAndCreatedAtGreaterThanAndSenderTypeNot(c.getId(), lastRead, "user");
        return new ConversationDto(c.getId(), c.getType(),
                c.getCategory() == null ? CATEGORY_CHAT : c.getCategory(),
                c.getName() == null ? "" : c.getName(), agentId,
                ids, c.getChatMode() == null ? "passive" : c.getChatMode(), c.isPinned(),
                c.getLastMessage() == null ? "" : c.getLastMessage(),
                c.getLastMessageAt(), unread, c.getArchivedAt(), c.getChannel(), c.getWechatPeer());
    }

    private Conversation baseConversation(String userId, long now, String type, String category, String name) {
        Conversation c = new Conversation();
        c.setId(Ids.next());
        c.setUserId(userId);
        c.setType(type);
        c.setCategory(category);
        c.setName(name);
        c.setChatMode("passive");
        c.setPinned(false);
        c.setLastMessage("");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    /** 新建会话并落库：任务会话等外部建群场景复用，其余字段取默认（passive、未置顶、空预览） */
    public Conversation newConversation(String userId, String type, String category, String name) {
        return newConversation(userId, type, category, name, null);
    }

    /** 带来源通道的建会话：微信等外部通道用 channel 标记 */
    public Conversation newConversation(String userId, String type, String category, String name, String channel) {
        Conversation c = baseConversation(userId, System.currentTimeMillis(), type, category, name);
        c.setChannel(channel);
        return conversations.save(c);
    }

    private List<String> memberIds(String conversationId) {
        return members.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(ConversationMember::getAgentId).toList();
    }

    private void deleteAll(Conversation c) {
        scheduledTasks.cancelAllForConversation(c.getId());
        messages.deleteByConversationId(c.getId());
        members.deleteByConversationId(c.getId());
        fileGrants.deleteByConversationId(c.getId());
        operationGrants.deleteByConversationId(c.getId());
        conversations.delete(c);
    }
}
