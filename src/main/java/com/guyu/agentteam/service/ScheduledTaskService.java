package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.CurrentUser;
import com.guyu.agentteam.common.Ids;
import com.guyu.agentteam.dto.ConversationDto;
import com.guyu.agentteam.dto.TaskDto;
import com.guyu.agentteam.dto.TaskGroupDto;
import com.guyu.agentteam.dto.TaskUpsertRequest;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.AppLog;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationMember;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.entity.ScheduledTask;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.repository.ConversationMemberRepository;
import com.guyu.agentteam.repository.ConversationRepository;
import com.guyu.agentteam.repository.MessageRepository;
import com.guyu.agentteam.repository.ScheduledTaskRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

/**
 * 定时任务：到点向绑定会话发一条合成用户消息（前缀【定时任务·名称】，落库带 taskId/taskName），
 * 复用 ChatStreamService.stream() 全管线（回复、协作、落库、扇出）。会话绑定两类：
 * 无成员 → 智能体专属任务线程（category=task 单聊，同智能体多任务共用）；有成员 → 任务项目群（category=task 群聊）。
 * 调度为进程内 TaskScheduler + 句柄表，启动时恢复；错过的一次性任务 24h 内补发，超时跳过；周期任务只算未来。
 */
@Service
public class ScheduledTaskService {

    private static final long CATCHUP_MS = 24L * 60 * 60 * 1000;
    private static final long POSTPONE_MS = 60_000;
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 归档会话上保存的任务配置快照（恢复任务时按此重建） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TaskSnapshot(String agentId, List<String> memberIds, String name, String content,
                        String kind, Long runAt, String timeOfDay, String daysOfWeek,
                        Integer intervalMinutes, String mode, Boolean catchUp,
                        Boolean autoWrite, Boolean autoShell) {
    }

    private final ScheduledTaskRepository tasks;
    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final AgentRepository agents;
    private final MessageRepository messages;
    private final MessageService messageService;
    private final AppLogService appLogs;
    private final ObjectProvider<ChatStreamService> chatStream;

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public ScheduledTaskService(ScheduledTaskRepository tasks, ConversationRepository conversations,
                                ConversationMemberRepository members, AgentRepository agents,
                                MessageRepository messages, MessageService messageService,
                                AppLogService appLogs, ObjectProvider<ChatStreamService> chatStream) {
        this.tasks = tasks;
        this.conversations = conversations;
        this.members = members;
        this.agents = agents;
        this.messages = messages;
        this.messageService = messageService;
        this.appLogs = appLogs;
        this.chatStream = chatStream;
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("sched-task-");
        scheduler.initialize();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }

    // ---------- 查询 ----------

    /** 任务页数据：用户全部任务会话（类别 task、未归档）及其任务；无任务的会话不出现 */
    @Transactional(readOnly = true)
    public List<TaskGroupDto> listGroups(String userId) {
        List<TaskGroupDto> out = new ArrayList<>();
        for (Conversation c : conversations.findByUserIdAndCategoryAndArchivedAtIsNullOrderByLastMessageAtDesc(
                userId, ConversationService.CATEGORY_TASK)) {
            List<TaskDto> list = tasks.findByConversationIdOrderByNextRunAtAsc(c.getId())
                    .stream().map(TaskDto::from).toList();
            if (list.isEmpty()) {
                continue;
            }
            out.add(new TaskGroupDto(toDto(c), list));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<TaskDto> listByConversation(String conversationId) {
        return tasks.findByConversationIdOrderByNextRunAtAsc(conversationId)
                .stream().map(TaskDto::from).toList();
    }

    /** 任务会话列表（任务页左侧）：category=task、未归档、名下至少还有一条任务，按最近消息倒序 */
    @Transactional(readOnly = true)
    public List<ConversationDto> listTaskConversations(String userId) {
        List<Conversation> convs = conversations.findByUserIdAndCategoryAndArchivedAtIsNullOrderByLastMessageAtDesc(
                userId, ConversationService.CATEGORY_TASK);
        if (convs.isEmpty()) {
            return List.of();
        }
        Set<String> withTasks = tasks.findByConversationIdIn(
                        convs.stream().map(Conversation::getId).toList()).stream()
                .map(ScheduledTask::getConversationId).collect(Collectors.toSet());
        return convs.stream().filter(c -> withTasks.contains(c.getId())).map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<TaskDto> listByAgent(String agentId) {
        return tasks.findByAgentIdOrderByNextRunAtAsc(agentId).stream().map(TaskDto::from).toList();
    }

    // ---------- CRUD ----------

    @Transactional
    public TaskDto create(String userId, TaskUpsertRequest req) {
        String name = req.name() == null ? "" : req.name().trim();
        String content = req.content() == null ? "" : req.content().trim();
        if (name.isEmpty() || content.isEmpty()) {
            throw ApiException.badRequest("任务名称和内容不能为空");
        }
        if (req.agentId() == null || req.agentId().isBlank()) {
            throw ApiException.badRequest("缺少 agentId");
        }
        Agent agent = agents.findById(req.agentId())
                .orElseThrow(() -> ApiException.badRequest("智能体不存在: " + req.agentId()));
        String kind = normalizeKind(req.kind());
        validateKind(kind, req.runAt(), req.timeOfDay(), req.daysOfWeek(), req.intervalMinutes(), true);
        // 防循环兜底：模型偶尔会把任务的触发消息误当成新请求反复建任务——该智能体下
        // 已有同名同内容（未完成）的任务时直接拒绝，阻断「触发→再建→再触发」的失控循环
        boolean duplicated = tasks.findByAgentIdOrderByNextRunAtAsc(agent.getId()).stream()
                .anyMatch(x -> !ScheduledTask.STATUS_DONE.equals(x.getStatus())
                        && name.equals(x.getName()) && content.equals(x.getContent()));
        if (duplicated) {
            throw ApiException.badRequest(
                    "已存在同名同内容的定时任务，请勿重复创建；如需调整时间或内容请用 update_scheduled_task");
        }

        List<String> memberIds = req.memberIds() == null ? List.of()
                : req.memberIds().stream().distinct()
                        .filter(id -> !id.equals(req.agentId())).toList();
        if (!memberIds.isEmpty()) {
            memberIds = resolveMemberIds(memberIds);
        }
        // 任务类型：普通=智能体独立执行（绑定其任务线程）；协作=编排者拉成员建任务项目群。
        // mode 未传时兼容旧入参/AI 工具：带成员即协作
        String mode = req.mode() == null || req.mode().isBlank()
                ? (memberIds.isEmpty() ? ScheduledTask.MODE_NORMAL : ScheduledTask.MODE_COLLAB)
                : (ScheduledTask.MODE_COLLAB.equals(req.mode()) ? ScheduledTask.MODE_COLLAB : ScheduledTask.MODE_NORMAL);
        if (ScheduledTask.MODE_COLLAB.equals(mode)) {
            if (memberIds.isEmpty()) {
                throw ApiException.badRequest("协作任务至少选择一名协作成员");
            }
        } else {
            memberIds = List.of();
        }
        Conversation conv = memberIds.isEmpty()
                ? ensureTaskThread(userId, agent.getId())
                : createTaskGroup(userId, agent.getId(), memberIds, name);

        long now = System.currentTimeMillis();
        ScheduledTask t = new ScheduledTask();
        t.setId(Ids.next());
        t.setConversationId(conv.getId());
        t.setAgentId(agent.getId());
        t.setName(name);
        t.setContent(content);
        t.setKind(kind);
        t.setRunAt(req.runAt());
        t.setTimeOfDay(req.timeOfDay());
        t.setDaysOfWeek(req.daysOfWeek());
        t.setIntervalMinutes(req.intervalMinutes());
        t.setMode(mode);
        t.setCatchUp(Boolean.TRUE.equals(req.catchUp()));
        // 后台无人盯审批：写改文件默认自动放行，执行命令默认不放行
        t.setAutoWrite(req.autoWrite() == null || req.autoWrite());
        t.setAutoShell(Boolean.TRUE.equals(req.autoShell()));
        t.setStatus(ScheduledTask.STATUS_ACTIVE);
        t.setCreatedAt(now);
        t.setNextRunAt(computeNext(t, now));
        tasks.save(t);
        schedule(t);
        appLogs.record(AppLog.TYPE_TASK, conv.getId(), agent.getId(),
                "创建定时任务「" + name + "」：" + describe(kind, req.timeOfDay(), req.daysOfWeek(), req.intervalMinutes()));
        return TaskDto.from(t);
    }

    /** 协作成员入参兼容 id 与名字（模型更常给出名字）：id 精确 → 名字精确 → 名字唯一包含；无法解析则报错 */
    private List<String> resolveMemberIds(List<String> tokens) {
        List<Agent> all = agents.findAll();
        List<String> resolved = new ArrayList<>();
        for (String token : tokens) {
            Agent hit = all.stream().filter(a -> a.getId().equals(token)).findFirst()
                    .orElseGet(() -> all.stream().filter(a -> token.equals(a.getName())).findFirst()
                            .orElse(null));
            if (hit == null) {
                List<Agent> fuzzy = all.stream()
                        .filter(a -> a.getName() != null && a.getName().contains(token)).toList();
                if (fuzzy.size() == 1) {
                    hit = fuzzy.get(0);
                }
            }
            if (hit == null) {
                throw ApiException.badRequest("未找到协作成员: " + token + "，请用 list_team 查询成员后重试");
            }
            if (!resolved.contains(hit.getId())) {
                resolved.add(hit.getId());
            }
        }
        return resolved;
    }

    @Transactional
    public TaskDto update(String taskId, TaskUpsertRequest req) {
        ScheduledTask t = tasks.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("定时任务不存在"));
        String oldName = t.getName();
        if (req.name() != null && !req.name().isBlank()) {
            t.setName(req.name().trim());
        }
        if (req.content() != null && !req.content().isBlank()) {
            t.setContent(req.content().trim());
        }
        String kind = req.kind() != null ? normalizeKind(req.kind()) : t.getKind();
        Long runAt = req.runAt() != null ? req.runAt() : t.getRunAt();
        String timeOfDay = req.timeOfDay() != null ? req.timeOfDay() : t.getTimeOfDay();
        String daysOfWeek = req.daysOfWeek() != null ? req.daysOfWeek() : t.getDaysOfWeek();
        Integer interval = req.intervalMinutes() != null ? req.intervalMinutes() : t.getIntervalMinutes();
        // once 的时间改为过去视为非法，其余字段沿用原值校验
        validateKind(kind, runAt, timeOfDay, daysOfWeek, interval, req.runAt() != null);
        t.setKind(kind);
        t.setRunAt(runAt);
        t.setTimeOfDay(timeOfDay);
        t.setDaysOfWeek(daysOfWeek);
        t.setIntervalMinutes(interval);
        if (req.catchUp() != null) {
            t.setCatchUp(req.catchUp());
        }
        if (req.autoWrite() != null) {
            t.setAutoWrite(req.autoWrite());
        }
        if (req.autoShell() != null) {
            t.setAutoShell(req.autoShell());
        }

        if (req.status() != null && (ScheduledTask.STATUS_PAUSED.equals(req.status())
                || ScheduledTask.STATUS_ACTIVE.equals(req.status()))) {
            t.setStatus(req.status());
        }
        if (ScheduledTask.STATUS_ACTIVE.equals(t.getStatus())) {
            t.setNextRunAt(computeNext(t, System.currentTimeMillis()));
            schedule(t);
        } else {
            cancelFuture(t.getId());
            t.setNextRunAt(null);
        }
        tasks.save(t);
        if (!oldName.equals(t.getName())) {
            syncGroupName(t);
        }
        return TaskDto.from(t);
    }

    @Transactional
    public void delete(String taskId) {
        ScheduledTask t = tasks.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("定时任务不存在"));
        cancelFuture(t.getId());
        tasks.delete(t);
        archiveTaskMessages(t);
        appLogs.record(AppLog.TYPE_TASK, t.getConversationId(), t.getAgentId(), "删除定时任务「" + t.getName() + "」");
    }

    /**
     * 被删任务的消息归档：整批移入一条新建的归档会话（category=task + archivedAt）。
     * 任务线程里随之不再显示这批消息；历史记录页把该会话按「定时任务」标签区分展示，
     * 并保存任务配置快照供「恢复任务」重建。
     */
    private void archiveTaskMessages(ScheduledTask t) {
        List<Message> msgs = messages.findByConversationIdAndTaskIdOrderByCreatedAtAsc(
                t.getConversationId(), t.getId());
        if (msgs.isEmpty()) return;
        long now = System.currentTimeMillis();
        Message last = msgs.get(msgs.size() - 1);
        Conversation archive = new Conversation();
        archive.setId(Ids.next());
        archive.setUserId(CurrentUser.ID);
        archive.setType("single");
        archive.setCategory(ConversationService.CATEGORY_TASK);
        archive.setName(t.getName());
        archive.setChatMode("passive");
        archive.setLastMessage(last.getContent());
        archive.setLastMessageAt(last.getCreatedAt());
        archive.setLastReadAt(now);
        archive.setCreatedAt(now);
        archive.setUpdatedAt(now);
        archive.setArchivedAt(now);
        archive.setTaskSnapshot(taskSnapshotJson(t));
        conversations.save(archive);
        members.save(new ConversationMember(archive.getId(), t.getAgentId(), now));
        for (Message m : msgs) {
            m.setConversationId(archive.getId());
        }
        messages.saveAll(msgs);
        // 原线程的预览可能指向被移走的消息，改指向剩余最新一条
        conversations.findById(t.getConversationId()).ifPresent(thread -> {
            List<Message> remaining = messages.findByConversationIdOrderByCreatedAtDesc(
                    thread.getId(), PageRequest.of(0, 1));
            thread.setLastMessage(remaining.isEmpty() ? "" : remaining.get(0).getContent());
            thread.setLastMessageAt(remaining.isEmpty() ? null : remaining.get(0).getCreatedAt());
            thread.setUpdatedAt(now);
            conversations.save(thread);
        });
    }

    private String taskSnapshotJson(ScheduledTask t) {
        try {
            List<String> memberIds = memberIds(t.getConversationId()).stream()
                    .filter(id -> !id.equals(t.getAgentId())).toList();
            return MAPPER.writeValueAsString(new TaskSnapshot(t.getAgentId(), memberIds, t.getName(),
                    t.getContent(), t.getKind(), t.getRunAt(), t.getTimeOfDay(), t.getDaysOfWeek(),
                    t.getIntervalMinutes(), t.getMode(), t.isCatchUp(), t.isAutoWrite(), t.isAutoShell()));
        } catch (Exception e) {
            throw new IllegalStateException("任务快照序列化失败", e);
        }
    }

    /** 从历史归档恢复定时任务：按快照走 create() 重建（once 已过期则顺延 1 分钟执行），
     *  并把归档消息搬回任务线程挂到新任务名下、删除归档会话——恢复后历史页不再出现 */
    @Transactional
    public TaskDto restoreFromArchive(String archiveConversationId) {
        Conversation archive = conversations.findById(archiveConversationId)
                .orElseThrow(() -> ApiException.notFound("归档会话不存在"));
        if (!ConversationService.CATEGORY_TASK.equals(archive.getCategory())
                || archive.getTaskSnapshot() == null || archive.getTaskSnapshot().isBlank()) {
            throw ApiException.badRequest("该归档缺少任务配置，无法恢复");
        }
        TaskSnapshot s;
        try {
            s = MAPPER.readValue(archive.getTaskSnapshot(), TaskSnapshot.class);
        } catch (Exception e) {
            throw ApiException.badRequest("任务配置快照解析失败，无法恢复");
        }
        Long runAt = s.runAt();
        if (ScheduledTask.KIND_ONCE.equals(s.kind()) && runAt != null && runAt <= System.currentTimeMillis()) {
            runAt = System.currentTimeMillis() + POSTPONE_MS;
        }
        // 旧快照无 mode/catch_up/auto_* 字段：按成员有无推导类型、补发默认关、放行走默认值
        String mode = s.mode() == null
                ? (s.memberIds().isEmpty() ? ScheduledTask.MODE_NORMAL : ScheduledTask.MODE_COLLAB)
                : s.mode();
        TaskDto dto = create(CurrentUser.ID, new TaskUpsertRequest(s.agentId(), s.memberIds(), s.name(),
                s.content(), s.kind(), runAt, s.timeOfDay(), s.daysOfWeek(), s.intervalMinutes(), null,
                mode, s.catchUp() != null && s.catchUp(), s.autoWrite(), s.autoShell()));
        long now = System.currentTimeMillis();
        List<Message> msgs = messages.findByConversationIdOrderByCreatedAtAsc(archive.getId());
        for (Message m : msgs) {
            m.setConversationId(dto.conversationId());
            m.setTaskId(dto.id());
            m.setTaskName(dto.name());
        }
        messages.saveAll(msgs);
        members.findByConversationIdOrderByCreatedAtAsc(archive.getId()).forEach(members::delete);
        conversations.delete(archive);
        // 线程预览改指向最新一条（含搬回的消息）
        conversations.findById(dto.conversationId()).ifPresent(thread -> {
            List<Message> latest = messages.findByConversationIdOrderByCreatedAtDesc(
                    thread.getId(), PageRequest.of(0, 1));
            thread.setLastMessage(latest.isEmpty() ? "" : latest.get(0).getContent());
            thread.setLastMessageAt(latest.isEmpty() ? null : latest.get(0).getCreatedAt());
            thread.setUpdatedAt(now);
            conversations.save(thread);
        });
        appLogs.record(AppLog.TYPE_TASK, dto.conversationId(), dto.agentId(),
                "从历史归档恢复定时任务「" + dto.name() + "」");
        return dto;
    }

    /** 立即执行一次：手动触发与到点相同的执行流程；周期任务的下一次排期不受影响，暂停中的任务执行后保持暂停 */
    @Transactional
    public TaskDto runNow(String taskId) {
        ScheduledTask t = tasks.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("定时任务不存在"));
        Conversation conv = conversations.findById(t.getConversationId()).orElse(null);
        if (conv == null || conv.getArchivedAt() != null) {
            throw ApiException.badRequest("任务会话不存在或已归档");
        }
        ChatStreamService stream = chatStream.getIfAvailable();
        if (stream != null && stream.isRunning(conv.getId())) {
            throw ApiException.badRequest("上一轮回复尚未结束，请稍后再试");
        }
        boolean paused = ScheduledTask.STATUS_PAUSED.equals(t.getStatus());
        fire(t, false);
        if (paused) {
            // fire 会顺带计算下一次时间，暂停中的任务手动执行完应保持无排期
            tasks.findById(taskId).ifPresent(x -> {
                x.setNextRunAt(null);
                tasks.save(x);
            });
        }
        appLogs.record(AppLog.TYPE_TASK, conv.getId(), t.getAgentId(),
                "定时任务「" + t.getName() + "」手动立即执行");
        return tasks.findById(taskId).map(TaskDto::from).orElse(TaskDto.from(t));
    }

    /** 批量暂停/恢复某会话的全部未完成任务（任务页主体右键），返回受影响数量 */
    @Transactional
    public int updateStatusByConversation(String conversationId, String status) {
        if (!ScheduledTask.STATUS_ACTIVE.equals(status) && !ScheduledTask.STATUS_PAUSED.equals(status)) {
            throw ApiException.badRequest("非法状态: " + status);
        }
        int changed = 0;
        for (ScheduledTask t : tasks.findByConversationIdOrderByNextRunAtAsc(conversationId)) {
            if (ScheduledTask.STATUS_DONE.equals(t.getStatus()) || status.equals(t.getStatus())) continue;
            t.setStatus(status);
            if (ScheduledTask.STATUS_ACTIVE.equals(status)) {
                t.setNextRunAt(computeNext(t, System.currentTimeMillis()));
                schedule(t);
            } else {
                cancelFuture(t.getId());
                t.setNextRunAt(null);
            }
            tasks.save(t);
            changed++;
        }
        if (changed > 0) {
            appLogs.record(AppLog.TYPE_TASK, conversationId, null,
                    ScheduledTask.STATUS_PAUSED.equals(status) ? "批量暂停全部定时任务" : "批量恢复全部定时任务");
        }
        return changed;
    }

    /** 批量删除某会话的全部任务（逐个走单删的归档流程），返回删除数量 */
    @Transactional
    public int deleteAllForConversation(String conversationId) {
        int deleted = 0;
        for (ScheduledTask t : tasks.findByConversationIdOrderByNextRunAtAsc(conversationId)) {
            delete(t.getId());
            deleted++;
        }
        return deleted;
    }

    /** 会话级联清理：删除/解散/归档会话时取消其全部任务 */
    @Transactional
    public void cancelAllForConversation(String conversationId) {
        for (ScheduledTask t : tasks.findByConversationIdOrderByNextRunAtAsc(conversationId)) {
            cancelFuture(t.getId());
        }
        tasks.deleteByConversationId(conversationId);
    }

    /** 任务改名后同步任务项目群名（群名固定为「任务：xxx」时才跟随） */
    private void syncGroupName(ScheduledTask t) {
        conversations.findById(t.getConversationId()).ifPresent(conv -> {
            if ("group".equals(conv.getType())
                    && ConversationService.CATEGORY_TASK.equals(conv.getCategory())
                    && conv.getName() != null && conv.getName().startsWith("任务：")) {
                conv.setName("任务：" + t.getName());
                conv.setUpdatedAt(System.currentTimeMillis());
                conversations.save(conv);
            }
        });
    }

    // ---------- 会话解析 ----------

    /** 智能体专属任务线程：同智能体共用一条（category=task 单聊），没有则创建 */
    private Conversation ensureTaskThread(String userId, String agentId) {
        for (Conversation c : conversations.findByUserIdAndCategoryAndArchivedAtIsNullOrderByLastMessageAtDesc(
                userId, ConversationService.CATEGORY_TASK)) {
            if ("single".equals(c.getType()) && memberIds(c.getId()).contains(agentId)) {
                return c;
            }
        }
        agents.findById(agentId).orElseThrow(() -> ApiException.badRequest("智能体不存在: " + agentId));
        long now = System.currentTimeMillis();
        Conversation c = new Conversation();
        c.setId(Ids.next());
        c.setUserId(userId);
        c.setType("single");
        c.setCategory(ConversationService.CATEGORY_TASK);
        c.setName("");
        c.setChatMode("passive");
        c.setLastMessage("");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        conversations.save(c);
        members.save(new ConversationMember(c.getId(), agentId, now));
        return c;
    }

    /** 任务项目群：编排者/用户拉成员执行定时任务时创建（发起智能体 + 成员 + 用户） */
    private Conversation createTaskGroup(String userId, String agentId, List<String> memberIds, String taskName) {
        for (String id : memberIds) {
            agents.findById(id).orElseThrow(() -> ApiException.badRequest("智能体不存在: " + id));
        }
        long now = System.currentTimeMillis();
        Conversation c = new Conversation();
        c.setId(Ids.next());
        c.setUserId(userId);
        c.setType("group");
        c.setCategory(ConversationService.CATEGORY_TASK);
        c.setName("任务：" + taskName);
        c.setChatMode("passive");
        c.setLastMessage("");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        conversations.save(c);
        members.save(new ConversationMember(c.getId(), agentId, now));
        for (String id : memberIds) {
            members.save(new ConversationMember(c.getId(), id, now));
        }
        return c;
    }

    private List<String> memberIds(String conversationId) {
        return members.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(ConversationMember::getAgentId).toList();
    }

    // ---------- 调度与触发 ----------

    /** 启动恢复：开启补发的过期一次性任务 24h 内补发、超时跳过，未开启补发直接跳过；周期任务重排到下一个未来时间 */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        long now = System.currentTimeMillis();
        for (ScheduledTask t : tasks.findByStatus(ScheduledTask.STATUS_ACTIVE)) {
            if (ScheduledTask.KIND_ONCE.equals(t.getKind())) {
                if (t.getRunAt() != null && t.getRunAt() <= now) {
                    if (t.isCatchUp() && now - t.getRunAt() <= CATCHUP_MS) {
                        fire(t, true);
                    } else {
                        t.setStatus(ScheduledTask.STATUS_DONE);
                        tasks.save(t);
                        appLogs.record(AppLog.TYPE_TASK, t.getConversationId(), t.getAgentId(),
                                "定时任务「" + t.getName() + "」错过定时" + (t.isCatchUp() ? "超过 24 小时" : "且未开启补发") + "，已跳过");
                    }
                    continue;
                }
                t.setNextRunAt(t.getRunAt());
                tasks.save(t);
                schedule(t);
                continue;
            }
            t.setNextRunAt(computeNext(t, now));
            tasks.save(t);
            schedule(t);
        }
    }

    private void schedule(ScheduledTask t) {
        cancelFuture(t.getId());
        if (!ScheduledTask.STATUS_ACTIVE.equals(t.getStatus()) || t.getNextRunAt() == null) {
            return;
        }
        long delay = Math.max(0, t.getNextRunAt() - System.currentTimeMillis());
        futures.put(t.getId(), scheduler.schedule(() -> runSafely(t.getId()),
                Instant.now().plusMillis(delay)));
    }

    private void cancelFuture(String taskId) {
        ScheduledFuture<?> prev = futures.remove(taskId);
        if (prev != null) {
            prev.cancel(false);
        }
    }

    /** 触发时以 DB 最新状态为准，避免持有过期快照 */
    private void runSafely(String taskId) {
        futures.remove(taskId);
        ScheduledTask t = tasks.findById(taskId).orElse(null);
        if (t == null || !ScheduledTask.STATUS_ACTIVE.equals(t.getStatus())) {
            return;
        }
        try {
            fire(t, false);
        } catch (Exception e) {
            appLogs.record(AppLog.TYPE_ERROR, t.getConversationId(), t.getAgentId(),
                    "定时任务「" + t.getName() + "」触发失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
            if (!ScheduledTask.KIND_ONCE.equals(t.getKind())) {
                t.setNextRunAt(System.currentTimeMillis() + POSTPONE_MS);
                tasks.save(t);
                schedule(t);
            } else {
                t.setStatus(ScheduledTask.STATUS_DONE);
                tasks.save(t);
            }
        }
    }

    private void fire(ScheduledTask t, boolean catchUp) {
        Conversation conv = conversations.findById(t.getConversationId()).orElse(null);
        if (conv == null || conv.getArchivedAt() != null) {
            cancelFuture(t.getId());
            tasks.delete(t);
            return;
        }
        ChatStreamService stream = chatStream.getIfAvailable();
        // 上一轮未结束：顺延一分钟，避免同一会话两轮流交错
        if (stream != null && stream.isRunning(conv.getId())) {
            t.setNextRunAt(System.currentTimeMillis() + POSTPONE_MS);
            tasks.save(t);
            schedule(t);
            appLogs.record(AppLog.TYPE_TASK, conv.getId(), t.getAgentId(),
                    "定时任务「" + t.getName() + "」触发时会话忙碌，已顺延 1 分钟");
            return;
        }
        String content = "【定时任务触发·" + t.getName() + (catchUp ? "·错过补发" : "") + "】\n"
                + "这是已存在的定时任务到点的自动触发，不是用户新的任务请求，也不要调用 schedule_task 再创建任务。\n"
                + "请在本轮真正开始执行下面的任务内容：需要委派成员就直接调用 delegate 工具（当前任务群已就绪，无需再建群），"
                + "需要写改文件就实际调用文件工具；任务内容若提到「以任务卡形式委派」，指的是把任务卡内容作为 delegate 的参数传给成员，"
                + "而不是把任务卡作为聊天文本输出。不要只在回复里输出计划、任务卡或总结，"
                + "也不要因为历史记录里已有类似执行结果就跳过本轮执行：\n" + t.getContent();
        Message m = messageService.createUserMessage(conv, content, List.of());
        m.setTaskId(t.getId());
        m.setTaskName(t.getName());
        messages.save(m);
        long now = System.currentTimeMillis();
        t.setLastRunAt(now);
        if (ScheduledTask.KIND_ONCE.equals(t.getKind())) {
            t.setStatus(ScheduledTask.STATUS_DONE);
        } else {
            t.setNextRunAt(computeNext(t, now));
        }
        tasks.save(t);
        appLogs.record(AppLog.TYPE_TASK, conv.getId(), t.getAgentId(),
                "定时任务「" + t.getName() + "」触发" + (catchUp ? "（错过补发）" : ""));
        if (stream != null) {
            stream.stream(new ConversationStreamSupport.Broadcast(conv.getId()), conv, m);
        }
        if (!ScheduledTask.KIND_ONCE.equals(t.getKind())) {
            schedule(t);
        }
    }

    // ---------- 时间计算与校验 ----------

    private String normalizeKind(String kind) {
        String k = kind == null ? "" : kind.trim();
        if (!ScheduledTask.KIND_ONCE.equals(k) && !ScheduledTask.KIND_DAILY.equals(k)
                && !ScheduledTask.KIND_WEEKLY.equals(k) && !ScheduledTask.KIND_INTERVAL.equals(k)) {
            throw ApiException.badRequest("未知的任务类型：" + kind + "（可选 once/daily/weekly/interval）");
        }
        return k;
    }

    /** strict=true 时要求字段齐全合法（创建时）；once 的 runAt 必须在未来（pastAllowed=false 时） */
    private void validateKind(String kind, Long runAt, String timeOfDay, String daysOfWeek,
                              Integer intervalMinutes, boolean futureRequired) {
        switch (kind) {
            case ScheduledTask.KIND_ONCE -> {
                if (runAt == null) {
                    throw ApiException.badRequest("once 类型必须提供 runAt");
                }
                if (futureRequired && runAt <= System.currentTimeMillis()) {
                    throw ApiException.badRequest("runAt 必须是未来的时间");
                }
            }
            case ScheduledTask.KIND_DAILY, ScheduledTask.KIND_WEEKLY -> {
                if (parseTime(timeOfDay) == null) {
                    throw ApiException.badRequest("timeOfDay 必须是 HH:mm 格式，例如 09:30");
                }
                if (ScheduledTask.KIND_WEEKLY.equals(kind)
                        && (parseDays(daysOfWeek) == null || parseDays(daysOfWeek).isEmpty())) {
                    throw ApiException.badRequest("weekly 类型必须提供 daysOfWeek（1-7 的数字，如 1,3,5）");
                }
            }
            case ScheduledTask.KIND_INTERVAL -> {
                if (intervalMinutes == null || intervalMinutes < 1) {
                    throw ApiException.badRequest("intervalMinutes 必须是大于等于 1 的整数");
                }
            }
            default -> throw ApiException.badRequest("未知的任务类型");
        }
    }

    /** 下一次触发时间（毫秒），无法计算返回 null */
    private Long computeNext(ScheduledTask t, long from) {
        LocalDateTime fromDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(from), ZONE);
        switch (t.getKind()) {
            case ScheduledTask.KIND_ONCE -> {
                return t.getRunAt();
            }
            case ScheduledTask.KIND_DAILY -> {
                LocalTime time = parseTime(t.getTimeOfDay());
                if (time == null) {
                    return null;
                }
                LocalDateTime candidate = LocalDateTime.of(fromDt.toLocalDate(), time);
                if (!candidate.isAfter(fromDt)) {
                    candidate = candidate.plusDays(1);
                }
                return candidate.atZone(ZONE).toInstant().toEpochMilli();
            }
            case ScheduledTask.KIND_WEEKLY -> {
                LocalTime time = parseTime(t.getTimeOfDay());
                Set<Integer> days = parseDays(t.getDaysOfWeek());
                if (time == null || days == null || days.isEmpty()) {
                    return null;
                }
                for (int i = 0; i < 8; i++) {
                    LocalDate d = fromDt.toLocalDate().plusDays(i);
                    if (!days.contains(d.getDayOfWeek().getValue())) {
                        continue;
                    }
                    LocalDateTime candidate = LocalDateTime.of(d, time);
                    if (candidate.isAfter(fromDt)) {
                        return candidate.atZone(ZONE).toInstant().toEpochMilli();
                    }
                }
                return null;
            }
            case ScheduledTask.KIND_INTERVAL -> {
                int minutes = t.getIntervalMinutes() == null ? 0 : t.getIntervalMinutes();
                if (minutes < 1) {
                    return null;
                }
                return from + minutes * 60_000L;
            }
            default -> {
                return null;
            }
        }
    }

    private LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** "1,3,5" → {1,3,5}（1=周一…7=周日），非法返回 null */
    private Set<Integer> parseDays(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            Set<Integer> days = java.util.Arrays.stream(s.split("[,，\\s]+"))
                    .filter(x -> !x.isBlank())
                    .map(Integer::parseInt)
                    .collect(Collectors.toSet());
            for (Integer d : days) {
                if (d < 1 || d > 7) {
                    return null;
                }
            }
            return days;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String describe(String kind, String timeOfDay, String daysOfWeek, Integer intervalMinutes) {
        return switch (kind) {
            case ScheduledTask.KIND_ONCE -> "单次";
            case ScheduledTask.KIND_DAILY -> "每天 " + (timeOfDay == null ? "" : timeOfDay);
            case ScheduledTask.KIND_WEEKLY -> "每周 " + (daysOfWeek == null ? "" : daysOfWeek) + " " + (timeOfDay == null ? "" : timeOfDay);
            case ScheduledTask.KIND_INTERVAL -> "每 " + intervalMinutes + " 分钟";
            default -> kind;
        };
    }

    // ---------- DTO ----------

    private ConversationDto toDto(Conversation c) {
        List<String> ids = memberIds(c.getId());
        String agentId = "single".equals(c.getType()) && !ids.isEmpty() ? ids.get(0) : null;
        long lastRead = c.getLastReadAt() == null ? 0L : c.getLastReadAt();
        long unread = messages.countByConversationIdAndCreatedAtGreaterThanAndSenderTypeNot(c.getId(), lastRead, "user");
        return new ConversationDto(c.getId(), c.getType(),
                c.getCategory() == null ? ConversationService.CATEGORY_CHAT : c.getCategory(),
                c.getName() == null ? "" : c.getName(), agentId,
                ids, c.getChatMode() == null ? "passive" : c.getChatMode(), c.isPinned(),
                c.getLastMessage() == null ? "" : c.getLastMessage(),
                c.getLastMessageAt(), unread, c.getArchivedAt());
    }
}
