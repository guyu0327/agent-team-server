package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, String> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    List<Message> findByConversationIdAndTaskIdOrderByCreatedAtAsc(String conversationId, String taskId);

    List<Message> findByConversationIdOrderByCreatedAtDesc(String conversationId, Pageable pageable);

    List<Message> findByConversationIdAndTaskIdOrderByCreatedAtDesc(String conversationId, String taskId, Pageable pageable);

    List<Message> findByConversationIdAndTaskIdAndCreatedAtLessThanOrderByCreatedAtDesc(
            String conversationId, String taskId, Long createdAt, Pageable pageable);

    List<Message> findByConversationIdAndCreatedAtLessThanOrderByCreatedAtDesc(String conversationId, Long createdAt, Pageable pageable);

    long countByConversationIdAndCreatedAtGreaterThanAndSenderTypeNot(String conversationId, Long createdAt, String senderType);

    /** 最近一条指定类型的消息（如 system 切换标注），无则 empty */
    Optional<Message> findFirstByConversationIdAndTypeOrderByCreatedAtDesc(String conversationId, String type);

    boolean existsByConversationId(String conversationId);

    void deleteByConversationId(String conversationId);

    /** 任务消息整批搬家（删除归档）：bulk update 避免逐条 load+save 长占 SQLite 单写连接 */
    @Modifying
    @Query("update Message m set m.conversationId = :to where m.conversationId = :from and m.taskId = :taskId")
    int moveTaskMessages(@Param("from") String from, @Param("to") String to, @Param("taskId") String taskId);

    /** 归档恢复：整批搬回任务线程并重挂任务归属 */
    @Modifying
    @Query("update Message m set m.conversationId = :to, m.taskId = :taskId, m.taskName = :taskName where m.conversationId = :from")
    int reassignMessagesToTask(@Param("from") String from, @Param("to") String to,
                               @Param("taskId") String taskId, @Param("taskName") String taskName);

    /** 只取带附件的消息（图片已阅标记用），长会话不再全量加载 */
    @Query("select m from Message m where m.conversationId = :conversationId"
            + " and m.attachments is not null and m.attachments <> ''")
    List<Message> findWithAttachmentsByConversationId(@Param("conversationId") String conversationId);
}
