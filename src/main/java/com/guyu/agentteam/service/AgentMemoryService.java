package com.guyu.agentteam.service;

import com.guyu.agentteam.dto.AgentMemoryDto;
import com.guyu.agentteam.entity.AgentMemory;
import com.guyu.agentteam.repository.AgentMemoryRepository;
import io.agentscope.core.message.Msg;
import io.agentscope.core.memory.LongTermMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
                return Mono.fromCallable(() -> retrieveAll(agentId))
                        .subscribeOn(Schedulers.boundedElastic());
            }
        };
    }

    private void recordAll(String agentId, List<Msg> msgs) {
        List<AgentMemory> fresh = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Msg m : msgs) {
            String text = m.getTextContent();
            if (text == null || text.isBlank()) continue;
            AgentMemory e = new AgentMemory();
            e.setId(UUID.randomUUID().toString());
            e.setAgentId(agentId);
            String trimmed = text.trim();
            e.setContent(trimmed.length() > MAX_ENTRY_CHARS ? trimmed.substring(0, MAX_ENTRY_CHARS) : trimmed);
            e.setCreatedAt(now);
            fresh.add(e);
        }
        if (fresh.isEmpty()) return;
        repo.saveAll(fresh);
        List<AgentMemory> all = repo.findByAgentIdOrderByCreatedAtAsc(agentId);
        if (all.size() > MAX_ENTRIES) {
            repo.deleteAll(all.subList(0, all.size() - MAX_ENTRIES));
        }
    }

    private String retrieveAll(String agentId) {
        List<AgentMemory> all = repo.findByAgentIdOrderByCreatedAtAsc(agentId);
        if (all.isEmpty()) return "（长期记忆为空）";
        StringBuilder sb = new StringBuilder("共 ").append(all.size()).append(" 条长期记忆：\n");
        for (int i = 0; i < all.size(); i++) {
            sb.append(i + 1).append(". ").append(all.get(i).getContent()).append('\n');
        }
        return sb.toString();
    }

    /** 注入系统提示的记忆块（时间正序、限预算），无记忆时返回空串 */
    public String promptBlock(String agentId) {
        List<AgentMemory> all = repo.findByAgentIdOrderByCreatedAtAsc(agentId);
        if (all.isEmpty()) return "";
        List<String> kept = new ArrayList<>();
        int used = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            String line = "- " + all.get(i).getContent();
            if (!kept.isEmpty() && used + line.length() > PROMPT_BUDGET_CHARS) break;
            kept.add(0, line);
            used += line.length();
        }
        return "【长期记忆】\n以下是你在过往交流中记下的关于用户的长期记忆：\n"
                + String.join("\n", kept)
                + "\n当用户透露值得长期记住的信息（身份背景、偏好、进行中的重要事项、明确要求记住的内容），"
                + "调用 recordToMemory 工具记下；寒暄与一次性的任务细节不要记。";
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
