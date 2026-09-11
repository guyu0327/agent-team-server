package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, String> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    List<Message> findByConversationIdOrderByCreatedAtDesc(String conversationId, Pageable pageable);

    List<Message> findByConversationIdAndCreatedAtLessThanOrderByCreatedAtDesc(String conversationId, Long createdAt, Pageable pageable);

    long countByConversationIdAndCreatedAtGreaterThanAndSenderTypeNot(String conversationId, Long createdAt, String senderType);

    void deleteByConversationId(String conversationId);
}
