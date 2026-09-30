package com.guyu.agentteam.service;

import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.common.SqlitePaths;
import com.guyu.agentteam.common.Str;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.repository.AgentRepository;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.skill.util.SkillFileSystemHelper;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 智能体技能接入的单一入口：技能目录 = 数据目录旁 skills/（智能体文件沙箱外）。
 * 绑定存技能目录名（skillId），构建 ReActAgent 时经 {@link #applySkills} 挂上
 * DynamicSkillMiddleware——框架在每次调用前重读目录并注入技能目录，改技能即时生效。
 */
@Service
public class SkillSupport {

    private final Path skillsDir;
    private final AgentRepository agents;

    public SkillSupport(AgentRepository agents,
                        @Value("${spring.datasource.url}") String datasourceUrl) {
        this.agents = agents;
        Path dbFile = SqlitePaths.dbFile(datasourceUrl);
        this.skillsDir = (dbFile != null ? dbFile.getParent() : Path.of("data")).resolve("skills");
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(skillsDir);
    }

    public Path skillsDir() {
        return skillsDir;
    }

    /**
     * 每次调用新建仓库实例：仓库内部会缓存目录快照且不感知外部文件改动，
     * 单例会让界面新建/编辑的技能永远进不了运行时注入；目录很小，重建开销可忽略
     */
    public AgentSkillRepository repository() {
        return new FileSystemSkillRepository(skillsDir, true, "local", true);
    }

    /** 逗号分隔存储转列表 */
    public static List<String> parseIds(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 智能体绑定了技能，或存在全局技能时才挂载；都没有则不加任何 builder 调用，行为与从前一致 */
    public void applySkills(ReActAgent.Builder builder, Agent agent) {
        List<String> ids = new ArrayList<>(parseIds(agent.getSkillIds()));
        for (String g : globalSkillIds()) {
            if (!ids.contains(g)) ids.add(g);
        }
        if (ids.isEmpty()) return;
        // 框架按 frontmatter 的 name（而非目录名）过滤与加载，绑定的目录 ID 需翻译成 name
        List<String> names = new ArrayList<>();
        for (String id : ids) {
            AgentSkill s = loadQuietly(id);
            if (s != null && !Str.isBlank(s.getName())) names.add(s.getName());
        }
        if (names.isEmpty()) return;
        builder.skillRepository(repository())
                .skillFilter(SkillFilter.only(names.toArray(String[]::new)))
                .dynamicSkillsEnabled(true);
    }

    /** 全局技能集合：技能目录下 scope.json 标记 global 的技能，对所有智能体生效 */
    public Set<String> globalSkillIds() {
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("SKILL.md")))
                    .filter(d -> {
                        Path scope = d.resolve("scope.json");
                        if (!Files.isRegularFile(scope)) return false;
                        try {
                            return Json.mapper().readTree(Files.readString(scope))
                                    .path("global").asBoolean(false);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(d -> d.getFileName().toString())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            return Set.of();
        }
    }

    /** 解析失败的技能（如手改坏 frontmatter）返回 null，不阻断其余技能挂载 */
    public AgentSkill loadQuietly(String skillId) {
        try {
            return SkillFileSystemHelper.loadSkillFromDirectory(skillsDir.resolve(skillId), "local");
        } catch (Exception e) {
            return null;
        }
    }

    /** 绑定了指定技能的智能体名（删除校验、列表计数用） */
    public List<String> boundAgentNames(String skillId) {
        List<String> names = new ArrayList<>();
        for (Agent a : agents.findByOrderByCreatedAtAsc()) {
            if (parseIds(a.getSkillIds()).contains(skillId)) names.add(a.getName());
        }
        return names;
    }
}
