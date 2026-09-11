package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, String> {

    List<Conversation> findByUserIdOrderByPinnedDescLastMessageAtDesc(String userId);

    List<Conversation> findByUserIdAndType(String userId, String type);
}
