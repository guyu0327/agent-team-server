package com.guyu.agentteam.controller;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.CurrentUser;
import com.guyu.agentteam.dto.AddMembersRequest;
import com.guyu.agentteam.dto.ConversationDto;
import com.guyu.agentteam.dto.FileGrantDto;
import com.guyu.agentteam.dto.GrantFilesRequest;
import com.guyu.agentteam.dto.GroupChatRequest;
import com.guyu.agentteam.dto.MessagePageDto;
import com.guyu.agentteam.dto.ModeRequest;
import com.guyu.agentteam.dto.NameRequest;
import com.guyu.agentteam.dto.PinRequest;
import com.guyu.agentteam.dto.SendRequest;
import com.guyu.agentteam.dto.SingleChatRequest;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationFileGrant;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.service.ChatStreamService;
import com.guyu.agentteam.service.ConversationService;
import com.guyu.agentteam.service.FileGrantService;
import com.guyu.agentteam.service.MessageService;
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

    public ConversationController(ConversationService conversationService, MessageService messageService,
                                  ChatStreamService chatStreamService, OrchestrationService orchestrationService,
                                  FileGrantService fileGrantService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.chatStreamService = chatStreamService;
        this.orchestrationService = orchestrationService;
        this.fileGrantService = fileGrantService;
    }

    @GetMapping
    public List<ConversationDto> list() {
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

    @DeleteMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable String id) {
        conversationService.reset(id);
    }

    @GetMapping("/{id}/messages")
    public MessagePageDto messages(@PathVariable String id,
                                   @RequestParam(required = false) Long before,
                                   @RequestParam(defaultValue = "50") int limit) {
        return conversationService.page(id, before, limit);
    }

    /**
     * 发送消息并流式接收智能体回复。SSE 事件：
     * user_message / reply_start / delta / reply_end / reply_error / done
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
        chatStreamService.stream(emitter, conv, userMsg);
        return emitter;
    }

    /** 本会话已授权的文件/目录（智能体文件工具可读写的范围） */
    @GetMapping("/{id}/files")
    public List<FileGrantDto> listFiles(@PathVariable String id) {
        return fileGrantService.list(id);
    }

    /** 登记文件/目录授权（文件选择弹窗中撤销前的单独授权） */
    @PostMapping("/{id}/files")
    public List<FileGrantDto> grantFiles(@PathVariable String id, @RequestBody GrantFilesRequest req) {
        return fileGrantService.grant(id, req.paths());
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
}
