package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.QqBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QqBindingRepository extends JpaRepository<QqBinding, String> {

    List<QqBinding> findByConversationId(String conversationId);
}
