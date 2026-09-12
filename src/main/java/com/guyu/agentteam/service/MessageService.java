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
        long now = System.currentTimeMillis();
        Message m = new Message();
        m.setId(Ids.next());
        m.setConversationId(conv.getId());
        m.setSenderType("user");
        m.setSenderId(conv.getUserId());
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
            preview = "[文件] " + grants.get(0).getName() + (grants.size() > 1 ? " 等 " + grants.size() + " 项" : "");
        }
        conv.setLastMessage(preview);
        conv.setLastMessageAt(now);
        conv.setUpdatedAt(now);
        conversations.save(conv);
        return m;
    }
}
