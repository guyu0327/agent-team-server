package com.guyu.agentteam.service;

import com.guyu.agentteam.common.Ids;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.repository.ConversationRepository;
import com.guyu.agentteam.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessageService {

    private final MessageRepository messages;
    private final ConversationRepository conversations;

    public MessageService(MessageRepository messages, ConversationRepository conversations) {
        this.messages = messages;
        this.conversations = conversations;
    }

    @Transactional
    public Message createUserMessage(Conversation conv, String content) {
        long now = System.currentTimeMillis();
        Message m = new Message();
        m.setId(Ids.next());
        m.setConversationId(conv.getId());
        m.setSenderType("user");
        m.setSenderId(conv.getUserId());
        m.setContent(content);
        m.setType("text");
        m.setCreatedAt(now);
        messages.save(m);

        conv.setLastMessage(content);
        conv.setLastMessageAt(now);
        conv.setUpdatedAt(now);
        conversations.save(conv);
        return m;
    }
}
