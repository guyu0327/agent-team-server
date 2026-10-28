package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.OperationGrant;
import com.guyu.agentteam.entity.OperationGrantId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperationGrantRepository extends JpaRepository<OperationGrant, OperationGrantId> {

    void deleteByConversationId(String conversationId);

    List<OperationGrant> findByConversationIdOrderByGrantedAtAsc(String conversationId);

    void deleteByConversationIdAndOpType(String conversationId, String opType);
}
