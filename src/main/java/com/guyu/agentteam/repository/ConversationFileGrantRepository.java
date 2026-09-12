package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.ConversationFileGrant;
import com.guyu.agentteam.entity.ConversationFileGrantId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationFileGrantRepository extends JpaRepository<ConversationFileGrant, ConversationFileGrantId> {

    List<ConversationFileGrant> findByConversationIdOrderByGrantedAtAsc(String conversationId);

    void deleteByConversationId(String conversationId);
}
