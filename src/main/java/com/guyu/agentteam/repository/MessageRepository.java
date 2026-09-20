package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, String> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    List<Message> findByConversationIdAndTaskIdOrderByCreatedAtAsc(String conversationId, String taskId);

    List<Message> findByConversationIdOrderByCreatedAtDesc(String conversationId, Pageable pageable);

    List<Message> findByConversationIdAndTaskIdOrderByCreatedAtDesc(String conversationId, String taskId, Pageable pageable);

    List<Message> findByConversationIdAndTaskIdAndCreatedAtLessThanOrderByCreatedAtDesc(
            String conversationId, String taskId, Long createdAt, Pageable pageable);

    List<Message> findByConversationIdAndCreatedAtLessThanOrderByCreatedAtDesc(String conversationId, Long createdAt, Pageable pageable);

    long countByConversationIdAndCreatedAtGreaterThanAndSenderTypeNot(String conversationId, Long createdAt, String senderType);

    boolean existsByConversationId(String conversationId);

    void deleteByConversationId(String conversationId);
}
