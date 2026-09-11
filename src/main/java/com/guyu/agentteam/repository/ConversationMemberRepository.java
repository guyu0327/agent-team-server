package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.ConversationMember;
import com.guyu.agentteam.entity.ConversationMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationMemberRepository extends JpaRepository<ConversationMember, ConversationMemberId> {

    List<ConversationMember> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    List<ConversationMember> findByAgentId(String agentId);

    void deleteByConversationId(String conversationId);
}
