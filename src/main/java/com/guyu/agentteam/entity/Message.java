package com.guyu.agentteam.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "messages")
public class Message {

    @Id
    private String id;
    private String conversationId;
    private String senderType;
    private String senderId;
    private String content;
    /** 附件 JSON：[{"path":"D:\\a\\b.py","type":"file","name":"b.py"}]，无附件为 null */
    private String attachments;
    private String type;
    private Long createdAt;
}
