package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.WechatBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WechatBindingRepository extends JpaRepository<WechatBinding, String> {

    List<WechatBinding> findByConversationId(String conversationId);
}
