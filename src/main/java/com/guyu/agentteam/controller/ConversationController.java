package com.guyu.agentteam.controller;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.CurrentUser;
import com.guyu.agentteam.dto.AddMembersRequest;
import com.guyu.agentteam.dto.AskAnswerRequest;
import com.guyu.agentteam.dto.ConversationDto;
import com.guyu.agentteam.dto.FileGrantDto;
import com.guyu.agentteam.dto.HostRequest;
import com.guyu.agentteam.dto.OpGrantDto;
import com.guyu.agentteam.dto.GroupChatRequest;
import com.guyu.agentteam.dto.MessageDto;
import com.guyu.agentteam.dto.MessagePageDto;
import com.guyu.agentteam.dto.ModeRequest;
import com.guyu.agentteam.dto.NameRequest;
import com.guyu.agentteam.dto.PinRequest;
import com.guyu.agentteam.dto.SendRequest;
import com.guyu.agentteam.dto.OpGrantRequest;
import com.guyu.agentteam.dto.SingleChatRequest;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationFileGrant;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.service.AskUserService;
import com.guyu.agentteam.service.ChatStreamService;
import com.guyu.agentteam.service.ConversationService;
import com.guyu.agentteam.service.ConversationStreamSupport;
import com.guyu.agentteam.service.FileGrantService;
import com.guyu.agentteam.service.MessageService;
import com.guyu.agentteam.service.OpApprovalService;
import com.guyu.agentteam.service.orchestration.OrchestrationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final ChatStreamService chatStreamService;
    private final OrchestrationService orchestrationService;
    private final FileGrantService fileGrantService;
    private final OpApprovalService opApproval;
    private final AskUserService askUsers;
    private final ConversationStreamSupport streamSupport;

    public ConversationController(ConversationService conversationService, MessageService messageService,
                                  ChatStreamService chatStreamService, OrchestrationService orchestrationService,
                                  FileGrantService fileGrantService, OpApprovalService opApproval,
                                  AskUserService askUsers, ConversationStreamSupport streamSupport) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.chatStreamService = chatStreamService;
        this.orchestrationService = orchestrationService;
        this.fileGrantService = fileGrantService;
        this.opApproval = opApproval;
        this.askUsers = askUsers;
        this.streamSupport = streamSupport;
    }

    @GetMapping
    public List<ConversationDto> list(@RequestParam(required = false) Boolean archived,
                                      @RequestParam(required = false) String agentId) {
        if (Boolean.TRUE.equals(archived)) {
            return conversationService.listArchived(CurrentUser.ID, agentId);
        }
        return conversationService.list(CurrentUser.ID);
    }

    @PostMapping("/single")
    public ConversationDto createSingle(@RequestBody SingleChatRequest req) {
        return conversationService.getOrCreateSingle(CurrentUser.ID, req.agentId());
    }

    @PostMapping("/group")
    public ConversationDto createGroup(@RequestBody GroupChatRequest req) {
        return conversationService.createGroup(CurrentUser.ID, req);
    }

    @PutMapping("/{id}/name")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rename(@PathVariable String id, @RequestBody NameRequest req) {
        conversationService.rename(id, req.name());
    }

    @PutMapping("/{id}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pin(@PathVariable String id, @RequestBody PinRequest req) {
        conversationService.setPinned(id, req.pinned());
    }

    /** 群聊聊天模式：passive（@谁谁回）| free（成员接龙自由讨论） */
    @PutMapping("/{id}/mode")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setMode(@PathVariable String id, @RequestBody ModeRequest req) {
        conversationService.setMode(id, req.mode());
    }

    /** 群聊编排者（会话角色）：agentId 为空串/null 表示清除，回到全员依次回复 */
    @PutMapping("/{id}/host")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setHost(@PathVariable String id, @RequestBody HostRequest req) {
        conversationService.setHost(id, req.agentId());
    }

    @PostMapping("/{id}/read")
    public ConversationDto markRead(@PathVariable String id) {
        return conversationService.markRead(id);
    }

    @PostMapping("/{id}/members")
    public ConversationDto addMembers(@PathVariable String id, @RequestBody AddMembersRequest req) {
        return conversationService.addMembers(id, req);
    }

    @DeleteMapping("/{id}/members/{agentId}")
    public Map<String, Object> removeMember(@PathVariable String id, @PathVariable String agentId) {
        return conversationService.removeMember(id, agentId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        conversationService.delete(id);
    }

    /** 归档到历史会话：先中断进行中的回复/协作，再落归档标记 */
    @PostMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable String id) {
        orchestrationService.stop(id);
        chatStreamService.stop(id);
        conversationService.archive(id);
    }

    /** 重置群聊：先中断进行中的回复/协作，旧群归档进历史会话，原群名/成员/聊天模式重建新群 */
    @PostMapping("/{id}/reset")
    public ConversationDto resetGroup(@PathVariable String id) {
        orchestrationService.stop(id);
        chatStreamService.stop(id);
        return conversationService.resetGroup(id);
    }

    /** 从历史会话恢复到消息列表（单聊冲突时原活跃会话自动入历史） */
    @PostMapping("/{id}/restore")
    public ConversationDto restore(@PathVariable String id) {
        return conversationService.restore(id);
    }

    @DeleteMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable String id) {
        conversationService.reset(id);
    }

    @GetMapping("/{id}/messages")
    public MessagePageDto messages(@PathVariable String id,
                                   @RequestParam(required = false) Long before,
                                   @RequestParam(required = false) String taskId,
                                   @RequestParam(defaultValue = "50") int limit) {
        return conversationService.page(id, before, limit, taskId);
    }

    /** 进入/刷新会话时重算上下文指示：与回合开始同一口径（系统提示 + 历史注入量），刷新后不必等下一轮回复 */
    @GetMapping("/{id}/context-size")
    public Map<String, Object> contextSize(@PathVariable String id) {
        return chatStreamService.contextSizeOf(conversationService.getEntity(id));
    }

    /**
     * 常驻事件订阅（EventSource）：会话有定时任务触发时，扇出该回合全部 SSE 事件，
     * 正在查看此会话的前端无需刷新即可实时看到输出。
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        conversationService.getEntity(id);
        SseEmitter emitter = new SseEmitter(0L);
        streamSupport.registerWatcher(id, emitter);
        try {
            emitter.send(SseEmitter.event().name("ready").data("{}", org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // 客户端已断开：onCompletion 会清理注册
        }
        return emitter;
    }

    /**
     * 发送消息并流式接收智能体回复。SSE 事件：
     * user_message / reply_start / delta / reply_end / reply_error / done
     * 上一轮还在跑时发送 = 插话：消息立即落库并扇出给观察者（气泡即时可见），本请求只回执
     * user_message+done 立即结束；进行中的成员说完手头回复后剩余原计划作废，以插话为新触发继续
     * （单聊接着回，被动群聊按插话重新选人，自由讨论由编排器重新选人）。
     */
    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable String id, @RequestBody SendRequest req) {
        List<String> paths = req == null || req.attachments() == null ? List.of()
                : req.attachments().stream().map(SendRequest.AttachmentInput::path).toList();
        boolean hasContent = req != null && req.content() != null && !req.content().isBlank();
        if (!hasContent && paths.isEmpty()) {
            throw ApiException.badRequest("消息内容不能为空");
        }
        Conversation conv = conversationService.getEntity(id);
        if ("wechat".equals(conv.getChannel())) {
            throw ApiException.badRequest("微信会话请在微信中继续对话");
        }
        long now = System.currentTimeMillis();
        // 附件全部校验通过才继续：任何一个路径无效都直接拒绝，避免半发送状态
        List<ConversationFileGrant> grants = paths.stream()
                .map(p -> fileGrantService.validate(conv.getId(), p, now))
                .toList();
        if (!grants.isEmpty()) {
            fileGrantService.registerAll(grants);
        }
        Message userMsg = messageService.createUserMessage(conv, hasContent ? req.content().trim() : "", grants);
        SseEmitter emitter = new SseEmitter(0L);
        boolean started = chatStreamService.stream(emitter, conv, userMsg);
        boolean interjected = false;
        while (!started && !interjected) {
            // 上一轮还在跑：转为插话。置位失败即回合恰好结束 → 改开新轮（消息已落库不会重复落库），
            // 若又撞上新开轮则回到插话尝试——两个后台级竞态叠加才会循环，实际一次即中
            if (chatStreamService.requestInterject(id, userMsg)) {
                interjected = true;
            } else {
                started = chatStreamService.stream(emitter, conv, userMsg);
            }
        }
        if (interjected) {
            // 插话受理：消息实时扇出给观察者（其他窗口/任务扇出订阅即时可见），本请求即刻回执结束
            streamSupport.broadcastToWatchers(id, "user_message", MessageDto.from(userMsg));
            chatStreamService.acknowledgeInterject(emitter, userMsg);
        }
        return emitter;
    }

    /** 本会话已授权的文件/目录（智能体文件工具可读写的范围） */
    @GetMapping("/{id}/files")
    public List<FileGrantDto> listFiles(@PathVariable String id) {
        return fileGrantService.list(id);
    }

    @DeleteMapping("/{id}/files")
    public Map<String, Object> revokeFile(@PathVariable String id, @RequestParam String path) {
        return Map.of("deleted", fileGrantService.revoke(id, path));
    }

    /** 终止该会话进行中的编排协作或自由讨论（在协作创建的项目群里调用同样有效） */
    @PostMapping("/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id) {
        return Map.of("stopped", orchestrationService.stop(id) | chatStreamService.stop(id));
    }

    /** 审批卡片决定受控操作（写入/修改/执行命令）：decision = once | conversation | deny */
    @PostMapping("/{id}/op-grant")
    public Map<String, Object> opGrant(@PathVariable String id, @RequestBody OpGrantRequest req) {
        req.validate();
        return Map.of("applied", opApproval.decide(req.requestId(), req.decision()));
    }

    /** 本会话已授予的工具授权（审批卡片「本会话允许」落库的类型级授权） */
    @GetMapping("/{id}/op-grants")
    public List<OpGrantDto> listOpGrants(@PathVariable String id) {
        return opApproval.listGrants(id);
    }

    /** 撤销本会话某一类工具授权（write/edit/shell/browser/computer），撤销即时生效 */
    @DeleteMapping("/{id}/op-grants/{opType}")
    public Map<String, Object> revokeOpGrant(@PathVariable String id, @PathVariable String opType) {
        return Map.of("revoked", opApproval.revokeGrant(id, opType));
    }

    /** 手动开启本会话某类工具授权（工具授权弹层开关）：等同审批卡「本会话允许」，开启后同类操作不再弹卡 */
    @PutMapping("/{id}/op-grants/{opType}")
    public Map<String, Object> grantOp(@PathVariable String id, @PathVariable String opType) {
        return Map.of("granted", opApproval.grantDirectly(id, opType));
    }

    /** 提问卡片回答：answer 为所选项文本或「其他」自由输入；applied=false 表示问题已超时失效 */
    @PostMapping("/{id}/ask-answer")
    public Map<String, Object> askAnswer(@PathVariable String id, @RequestBody AskAnswerRequest req) {
        req.validate();
        return Map.of("applied", askUsers.answer(req.requestId(), req.answer()));
    }
}
