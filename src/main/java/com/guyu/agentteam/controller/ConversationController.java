package com.guyu.agentteam.controller;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.CurrentUser;
import com.guyu.agentteam.dto.AddMembersRequest;
import com.guyu.agentteam.dto.ConversationDto;
import com.guyu.agentteam.dto.GroupChatRequest;
import com.guyu.agentteam.dto.MessagePageDto;
import com.guyu.agentteam.dto.NameRequest;
import com.guyu.agentteam.dto.PinRequest;
import com.guyu.agentteam.dto.SendRequest;
import com.guyu.agentteam.dto.SingleChatRequest;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.service.ChatStreamService;
import com.guyu.agentteam.service.ConversationService;
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

    public ConversationController(ConversationService conversationService, MessageService messageService,
                                  ChatStreamService chatStreamService, OrchestrationService orchestrationService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.chatStreamService = chatStreamService;
        this.orchestrationService = orchestrationService;
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
        if (req == null || req.content() == null || req.content().isBlank()) {
            throw ApiException.badRequest("消息内容不能为空");
        }
        Conversation conv = conversationService.getEntity(id);
        Message userMsg = messageService.createUserMessage(conv, req.content().trim());
        SseEmitter emitter = new SseEmitter(0L);
        chatStreamService.stream(emitter, conv, userMsg);
        return emitter;
    }

    /** 终止该会话进行中的编排协作（在协作创建的项目群里调用同样有效） */
    @PostMapping("/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id) {
        return Map.of("stopped", orchestrationService.stop(id));
    }
}
