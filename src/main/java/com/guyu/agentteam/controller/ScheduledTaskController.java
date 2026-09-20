package com.guyu.agentteam.controller;

import com.guyu.agentteam.common.CurrentUser;
import com.guyu.agentteam.dto.ConversationDto;
import com.guyu.agentteam.dto.TaskDto;
import com.guyu.agentteam.dto.TaskGroupDto;
import com.guyu.agentteam.dto.TaskUpsertRequest;
import com.guyu.agentteam.service.ScheduledTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 定时任务 REST：任务页列表、创建（无会话/带成员建群均可）、更新（含暂停/恢复）、删除、立即执行、历史归档恢复 */
@RestController
@RequestMapping("/api/tasks")
public class ScheduledTaskController {

    private final ScheduledTaskService scheduledTasks;

    public ScheduledTaskController(ScheduledTaskService scheduledTasks) {
        this.scheduledTasks = scheduledTasks;
    }

    /** 任务页数据：按会话分组返回全部任务（无任务的会话不出现） */
    @GetMapping
    public List<TaskGroupDto> list() {
        return scheduledTasks.listGroups(CurrentUser.ID);
    }

    @PostMapping
    public TaskDto create(@RequestBody TaskUpsertRequest req) {
        return scheduledTasks.create(CurrentUser.ID, req);
    }

    /** 从历史归档恢复定时任务（按归档会话上的任务快照重建，消息搬回任务线程并删除归档），返回新任务 */
    @PostMapping("/restore/{conversationId}")
    public TaskDto restore(@PathVariable String conversationId) {
        return scheduledTasks.restoreFromArchive(conversationId);
    }

    /** 立即执行一次（手动触发与到点相同的流程），返回更新后的任务 */
    @PostMapping("/{taskId}/run")
    public TaskDto runNow(@PathVariable String taskId) {
        return scheduledTasks.runNow(taskId);
    }

    @PutMapping("/{taskId}")
    public TaskDto update(@PathVariable String taskId, @RequestBody TaskUpsertRequest req) {
        return scheduledTasks.update(taskId, req);
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String taskId) {
        scheduledTasks.delete(taskId);
    }

    /** 某会话的全部任务（管理弹窗用） */
    @GetMapping("/conversation/{conversationId}")
    public List<TaskDto> listByConversation(@PathVariable String conversationId) {
        return scheduledTasks.listByConversation(conversationId);
    }

    /** 任务会话列表（任务页左侧，category=task） */
    @GetMapping("/conversations")
    public List<ConversationDto> listTaskConversations() {
        return scheduledTasks.listTaskConversations(CurrentUser.ID);
    }

    /** 批量暂停/恢复某会话的全部任务（body: {"status":"active"|"paused"}），返回受影响数量 */
    @PutMapping("/conversations/{conversationId}/status")
    public int updateConversationStatus(@PathVariable String conversationId, @RequestBody TaskUpsertRequest req) {
        return scheduledTasks.updateStatusByConversation(conversationId, req.status());
    }

    /** 批量删除某会话的全部任务（逐个归档到历史记录） */
    @DeleteMapping("/conversations/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversationTasks(@PathVariable String conversationId) {
        scheduledTasks.deleteAllForConversation(conversationId);
    }
}
