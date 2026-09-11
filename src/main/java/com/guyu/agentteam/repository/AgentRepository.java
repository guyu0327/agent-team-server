package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRepository extends JpaRepository<Agent, String> {

    List<Agent> findByOrderByCreatedAtAsc();

    long countByPresetId(String presetId);
}
