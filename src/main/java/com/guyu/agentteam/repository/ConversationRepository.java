package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, String> {

    List<Conversation> findByUserIdAndCategoryAndArchivedAtIsNullOrderByPinnedDescLastMessageAtDesc(
            String userId, String category);

    List<Conversation> findByUserIdAndTypeAndCategoryAndArchivedAtIsNull(
            String userId, String type, String category);

    List<Conversation> findByUserIdAndCategoryAndArchivedAtIsNotNullOrderByArchivedAtDesc(
            String userId, String category);

    List<Conversation> findByUserIdAndArchivedAtIsNotNullOrderByArchivedAtDesc(String userId);

    List<Conversation> findByUserIdAndCategoryAndArchivedAtIsNullOrderByLastMessageAtDesc(
            String userId, String category);
}
