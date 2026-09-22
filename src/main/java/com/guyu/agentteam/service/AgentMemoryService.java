package com.guyu.agentteam.service;

import com.guyu.agentteam.dto.AgentMemoryDto;
import com.guyu.agentteam.entity.AgentMemory;
import com.guyu.agentteam.repository.AgentMemoryRepository;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.memory.LongTermMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 智能体长期记忆（SQLite 存储）。
 * 框架以 AGENT_CONTROL 模式接入：由智能体自己调用 recordToMemory / retrieveFromMemory
 * 工具决定记什么、何时取回；本服务只提供按智能体隔离的存取实现与系统提示注入。
 */
@Service
public class AgentMemoryService {

    /** 单个智能体记忆条数上限，超出裁掉最旧的 */
    private static final int MAX_ENTRIES = 300;
    /** 单条记忆字符上限（模型输出兜底截断） */
    private static final int MAX_ENTRY_CHARS = 2000;
    /** 注入系统提示的字符预算，超出时只保留较新的记忆 */
    private static final int PROMPT_BUDGET_CHARS = 6000;
    /** 工具检索路径的返回字符预算：几百条记忆全量回注会撑爆小上下文模型 */
    private static final int RETRIEVE_BUDGET_CHARS = 8000;

    private final AgentMemoryRepository repo;

    public AgentMemoryService(AgentMemoryRepository repo) {
        this.repo = repo;
    }

    /** 框架 LongTermMemory 的按智能体实现，每次构建 ReActAgent 时创建，无状态 */
    public LongTermMemory store(String agentId) {
        return new LongTermMemory() {
            @Override
            public Mono<Void> record(List<Msg> msgs) {
                return Mono.<Void>fromRunnable(() -> recordAll(agentId, msgs))
                        .subscribeOn(Schedulers.boundedElastic());
            }

            @Override
            public Mono<String> retrieve(Msg query) {
                return Mono.fromCallable(() -> AgentMemoryService.this.retrieve(
                                agentId, query == null ? "" : query.getTextContent()))
                        .subscribeOn(Schedulers.boundedElastic());
            }
        };
    }

    private void recordAll(String agentId, List<Msg> msgs) {
        // 框架的 recordToMemory(remark, contents) 一次调用会传多条消息：remark 说明走 assistant 角色，
        // 真正的记忆正文走 user 角色；合并为一条落库，否则一次调用存两行（说明+正文）
        List<String> contents = new ArrayList<>();
        List<String> fallback = new ArrayList<>();
        for (Msg m : msgs) {
            String text = m.getTextContent();
            if (text == null || text.isBlank()) continue;
            (m.getRole() == MsgRole.USER ? contents : fallback).add(text.trim());
        }
        List<String> picked = contents.isEmpty() ? fallback : contents;
        if (picked.isEmpty()) return;
        String joined = String.join("\n", picked);
        AgentMemory e = new AgentMemory();
        e.setId(UUID.randomUUID().toString());
        e.setAgentId(agentId);
        e.setContent(joined.length() > MAX_ENTRY_CHARS ? joined.substring(0, MAX_ENTRY_CHARS) : joined);
        e.setCreatedAt(System.currentTimeMillis());
        repo.save(e);
        List<AgentMemory> all = repo.findByAgentIdOrderByCreatedAtAsc(agentId);
        if (all.size() > MAX_ENTRIES) {
            repo.deleteAll(all.subList(0, all.size() - MAX_ENTRIES));
        }
    }

    /**
     * 工具检索路径：按查询词过滤记忆，命中部分限预算返回（从最新往回取）；
     * 无查询词回退全部（仍限预算），全部不命中回退最新几条，避免检索空手而归。
     */
    private String retrieve(String agentId, String queryText) {
        List<AgentMemory> all = repo.findByAgentIdOrderByCreatedAtAsc(agentId);
        if (all.isEmpty()) return "（长期记忆为空）";
        List<String> keywords = keywordsOf(queryText);
        List<AgentMemory> matched = keywords.isEmpty() ? all
                : all.stream().filter(m -> keywords.stream().anyMatch(k -> m.getContent().contains(k))).toList();
        if (matched.isEmpty()) {
            matched = all.subList(Math.max(0, all.size() - 5), all.size());
        }
        ArrayDeque<String> kept = new ArrayDeque<>();
        int used = 0;
        for (int i = matched.size() - 1; i >= 0; i--) {
            String line = "- " + matched.get(i).getContent();
            if (!kept.isEmpty() && used + line.length() > RETRIEVE_BUDGET_CHARS) break;
            kept.addFirst(line);
            used += line.length();
        }
        return "共 " + all.size() + " 条长期记忆"
                + (keywords.isEmpty() ? "" : "，与查询相关的 " + matched.size() + " 条")
                + "：\n" + String.join("\n", kept);
    }

    /** 查询文本切关键词：按空白与中英文标点拆分，保留长度 ≥2 的片段 */
    private static List<String> keywordsOf(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String w : text.split("[\\s\\p{Punct}，。！？、；：「」『』（）【】]+")) {
            if (w.length() >= 2) {
                out.add(w);
            }
        }
        return out;
    }

    /**
     * 注入系统提示的记忆块（时间正序、限预算）。
     * 无记忆时也必须返回使用指引：模型（尤其角色扮演人设）没有指引就只会口头说「记住了」，
     * 根本不调 recordToMemory，导致用户以为记住了、库里却什么都没有。
     */
    public String promptBlock(String agentId) {
        List<AgentMemory> all = repo.findByAgentIdOrderByCreatedAtAsc(agentId);
        List<String> kept = new ArrayList<>();
        int used = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            String line = "- " + all.get(i).getContent();
            if (!kept.isEmpty() && used + line.length() > PROMPT_BUDGET_CHARS) break;
            kept.add(0, line);
            used += line.length();
        }
        String listText = kept.isEmpty() ? "（你目前还没有任何长期记忆。）" : String.join("\n", kept);
        return "【长期记忆】\n以下是你在过往交流中记下的关于用户的长期记忆：\n" + listText + "\n"
                + "记忆工具使用规则（系统最高优先级约束，优先于你的任何人设、语气与立场设定）：\n"
                + "1. 当用户透露值得长期记住的信息，"
                + "或明确要求你「记住」某件事时，无论你的人设态度如何，必须当轮调用 recordToMemory 工具把内容保存下来；\n"
                + "2. 调用成功后才可以在回复中向用户确认已记住；只在对话里说「记住了」而不调工具，等于欺骗用户；\n"
                + "3. 若你的人设不愿配合某个请求，也必须先调用工具完成记录，再在人设允许的范围内表达态度；\n"
                + "4. 寒暄与一次性的任务细节不要记，重复的记忆不用记。";
    }

    public List<AgentMemoryDto> list(String agentId) {
        return repo.findByAgentIdOrderByCreatedAtAsc(agentId).stream()
                .map(m -> new AgentMemoryDto(m.getId(), m.getContent(), m.getCreatedAt()))
                .toList()
                .reversed();
    }

    @org.springframework.transaction.annotation.Transactional
    public void clear(String agentId) {
        repo.deleteAllByAgent(agentId);
    }
}
