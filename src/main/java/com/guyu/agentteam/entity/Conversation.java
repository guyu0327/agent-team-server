package com.guyu.agentteam.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    private String id;
    private String userId;
    private String type;
    /** 会话类别：chat=普通聊天（消息页）| task=定时任务线程/群（任务页） */
    private String category;
    private String name;
    /** 来源通道：wechat=iLink 微信会话（桌面端只读），null=应用内会话 */
    private String channel;
    /** 微信好友短标识（shortId，后 8 位）：会话按好友建，列表里以「微信ClawBot-xxxx」区分 */
    private String wechatPeer;
    /** 聊天模式：passive（默认）| free，仅群聊有意义 */
    private String chatMode;
    private boolean pinned;
    private String lastMessage;
    private Long lastMessageAt;
    private Long lastReadAt;
    private Long createdAt;
    private Long updatedAt;
    /** 历史会话归档时间：非空表示已归档（重置/解散/删除后进入历史），活跃查询一律排除 */
    private Long archivedAt;
    /** 早期历史的滚动摘要（上下文压缩产物），非空时拼进智能体 system prompt */
    private String contextDigest;
    /** 摘要水位线：记录已被摘要覆盖的最后一条消息 id，其之前的消息不再注入上下文 */
    private String digestWatermark;
    /** 定时任务归档快照：删除任务归档消息时保存的任务配置 JSON，供历史页「恢复任务」重建 */
    private String taskSnapshot;
}
