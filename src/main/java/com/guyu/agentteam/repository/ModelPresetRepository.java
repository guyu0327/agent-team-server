package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.ModelPreset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelPresetRepository extends JpaRepository<ModelPreset, String> {

    List<ModelPreset> findByOrderByCreatedAtAsc();
}
