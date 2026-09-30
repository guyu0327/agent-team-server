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
    /** 定时任务回合标注：本条消息由哪个定时任务触发产生（合成用户消息与该轮回复共用） */
    private String taskId;
    private String taskName;
    /** 联网活动 JSON：[{"tool":"web_search","query":"…","sites":[…]},{"tool":"fetch_webpage","url":"…","title":"…"}]，无活动为 null */
    private String webActivity;
    /** 思考过程全文（推理模型）：仅回看展示，不进对话上下文；超长截断，无思考为 null */
    private String thinking;
    private Long createdAt;
}
