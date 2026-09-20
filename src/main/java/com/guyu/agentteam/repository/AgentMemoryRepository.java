package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.AgentMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentMemoryRepository extends JpaRepository<AgentMemory, String> {

    List<AgentMemory> findByAgentIdOrderByCreatedAtAsc(String agentId);

    @Modifying
    @Query("delete from AgentMemory m where m.agentId = :agentId")
    void deleteAllByAgent(@Param("agentId") String agentId);
}
