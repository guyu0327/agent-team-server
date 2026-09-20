package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.ScheduledTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, String> {

    List<ScheduledTask> findByConversationIdOrderByNextRunAtAsc(String conversationId);

    List<ScheduledTask> findByConversationIdIn(Collection<String> conversationIds);

    List<ScheduledTask> findByAgentIdOrderByNextRunAtAsc(String agentId);

    List<ScheduledTask> findByStatus(String status);

    void deleteByConversationId(String conversationId);
}
