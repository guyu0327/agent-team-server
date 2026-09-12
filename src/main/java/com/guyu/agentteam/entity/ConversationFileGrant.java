package com.guyu.agentteam.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 会话级文件授权：聊天中附加的文件/文件夹路径，对智能体的文件工具开放 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "conversation_file_grants")
@IdClass(ConversationFileGrantId.class)
public class ConversationFileGrant {

    public static final String TYPE_FILE = "file";
    public static final String TYPE_DIR = "dir";

    @Id
    private String conversationId;
    @Id
    private String path;
    private String type;
    private String name;
    private Long grantedAt;
}
