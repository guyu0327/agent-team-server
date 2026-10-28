package com.guyu.agentteam.service;

import com.guyu.agentteam.common.Ids;
import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationFileGrant;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.repository.ConversationRepository;
import com.guyu.agentteam.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MessageService {

    private final MessageRepository messages;
    private final ConversationRepository conversations;

    public MessageService(MessageRepository messages, ConversationRepository conversations) {
        this.messages = messages;
        this.conversations = conversations;
    }

    @Transactional
    public Message createUserMessage(Conversation conv, String content, List<ConversationFileGrant> grants) {
        return createUserMessage(conv, content, grants, null);
    }

    /** senderId 非空时按外部通道真实发送者落库（如 QQ 群成员 openid），供前端区分消息归属 */
    @Transactional
    public Message createUserMessage(Conversation conv, String content, List<ConversationFileGrant> grants,
                                     String senderId) {
        return createUserMessage(conv, content, grants, senderId, null);
    }

    /** senderName/senderRole 为外部通道随事件下发的发送者昵称与群角色（如 QQ author.username/member_role），气泡与历史展示用 */
    @Transactional
    public Message createUserMessage(Conversation conv, String content, List<ConversationFileGrant> grants,
                                     String senderId, String senderName) {
        return createUserMessage(conv, content, grants, senderId, senderName, null);
    }

    @Transactional
    public Message createUserMessage(Conversation conv, String content, List<ConversationFileGrant> grants,
                                     String senderId, String senderName, String senderRole) {
        long now = System.currentTimeMillis();
        Message m = new Message();
        m.setId(Ids.next());
        m.setConversationId(conv.getId());
        m.setSenderType("user");
        m.setSenderId(senderId == null || senderId.isBlank() ? conv.getUserId() : senderId);
        m.setSenderName(senderName == null || senderName.isBlank() ? null : senderName);
        m.setSenderRole(senderRole == null || senderRole.isBlank() ? null : senderRole);
        m.setContent(content);
        if (grants != null && !grants.isEmpty()) {
            m.setAttachments(Json.writeAttachments(grants.stream()
                    .map(g -> new Json.Attachment(g.getPath(), g.getType(), g.getName()))
                    .toList()));
        }
        m.setType("text");
        m.setCreatedAt(now);
        messages.save(m);

        String preview = content;
        if ((preview == null || preview.isBlank()) && grants != null && !grants.isEmpty()) {
            String label = ConversationFileGrant.TYPE_IMAGE.equals(grants.get(0).getType()) ? "[图片] " : "[文件] ";
            preview = label + grants.get(0).getName() + (grants.size() > 1 ? " 等 " + grants.size() + " 项" : "");
        }
        conv.setLastMessage(preview);
        conv.setLastMessageAt(now);
        conv.setUpdatedAt(now);
        conversations.save(conv);
        return m;
    }
}
