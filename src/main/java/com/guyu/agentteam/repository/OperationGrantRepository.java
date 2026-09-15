package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.OperationGrant;
import com.guyu.agentteam.entity.OperationGrantId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationGrantRepository extends JpaRepository<OperationGrant, OperationGrantId> {

    void deleteByConversationId(String conversationId);
}
