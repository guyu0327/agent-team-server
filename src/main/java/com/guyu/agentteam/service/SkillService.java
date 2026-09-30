package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.common.Str;
import com.guyu.agentteam.dto.SkillDetailDto;
import com.guyu.agentteam.dto.SkillSummaryDto;
import com.guyu.agentteam.dto.SkillUpsertRequest;
import io.agentscope.core.skill.AgentSkill;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 技能文件管理：data/skills/<skillId>/SKILL.md（frontmatter: name/description + Markdown 正文）。
 * 使用范围存同目录 scope.json（{"global":true}，缺省按非全局处理），删目录即随之清理。
 * 运行时读取走 {@link SkillSupport#repository()}，这里只管目录与文件的增删改查。
 */
@Service
public class SkillService {

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-_]{0,63}$");
    private static final String SKILL_FILE = "SKILL.md";
    private static final String SCOPE_FILE = "scope.json";

    private final SkillSupport support;

    public SkillService(SkillSupport support) {
        this.support = support;
    }

    public List<SkillSummaryDto> list() {
        // 框架按 frontmatter 的 name 识别技能（getSkillId() 是 name@source），这里用来源目录关联
        Map<Path, AgentSkill> parsed = new HashMap<>();
        for (AgentSkill s : support.repository().getAllSkills()) {
            s.getOriginDir().ifPresent(p -> parsed.put(p.toAbsolutePath().normalize(), s));
        }
        try (Stream<Path> dirs = Files.list(support.skillsDir())) {
            return dirs.filter(Files::isDirectory)
                    .map(d -> toSummary(d, parsed.get(d.toAbsolutePath().normalize())))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(SkillSummaryDto::name))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取技能目录：" + e.getMessage(), e);
        }
    }

    private SkillSummaryDto toSummary(Path dir, AgentSkill skill) {
        String id = dir.getFileName().toString();
        Path file = dir.resolve(SKILL_FILE);
        if (!Files.isRegularFile(file)) return null;
        Long updatedAt = mtimeOf(file);
        return new SkillSummaryDto(id,
                skill != null && !Str.isBlank(skill.getName()) ? skill.getName() : id,
                skill != null && skill.getDescription() != null ? skill.getDescription() : "",
                updatedAt,
                support.boundAgentNames(id).size(),
                isGlobalDir(dir));
    }

    public SkillDetailDto get(String skillId) {
        Path file = skillFileOf(skillId);
        if (!Files.isRegularFile(file)) throw ApiException.notFound("技能不存在");
        String content = stripFrontmatter(readSafely(file));
        AgentSkill skill = parseQuietly(skillId);
        Path dir = skillDirOf(skillId);
        return new SkillDetailDto(skillId,
                skill != null && !Str.isBlank(skill.getName()) ? skill.getName() : skillId,
                skill != null && skill.getDescription() != null ? skill.getDescription() : "",
                content, mtimeOf(file), isGlobalDir(dir),
                dir.toAbsolutePath().normalize().toString(), listResources(skillId));
    }

    /**
     * 技能目录里的辅助文件清单（相对路径，/ 分隔，排除 SKILL.md 与 scope.json）。
     * 容忍目录缺失/读取失败（返回空清单），供详情接口与 list_skills 工具共用。
     * GitHub 拉下来的技能目录带 .git，属版本库元数据，不算辅助文件。
     */
    public List<String> listResources(String skillId) {
        Path dir = skillDirOf(skillId);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !SKILL_FILE.equals(p) && !SCOPE_FILE.equals(p))
                    .filter(p -> !isVcsMetadata(p))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 路径任一段为 .git（目录或文件）即版本库元数据 */
    private boolean isVcsMetadata(String relativePath) {
        for (String seg : relativePath.split("/")) {
            if (".git".equals(seg)) return true;
        }
        return false;
    }

    /** 辅助文件数量/单文件大小上限，防止一次安装塞爆技能目录 */
    private static final int RESOURCE_MAX_COUNT = 50;
    private static final int RESOURCE_MAX_CHARS = 200_000;

    /**
     * 写入辅助文件（相对路径 → 文本内容）。路径白名单校验：/ 或 \ 分隔、禁 .. 与绝对路径、
     * 禁 Windows 非法字符与保留文件名；全部校验通过后才落盘（避免半截安装）。
     */
    public void writeResources(String skillId, Map<String, String> files) {
        if (files == null || files.isEmpty()) return;
        if (files.size() > RESOURCE_MAX_COUNT) {
            throw ApiException.badRequest("辅助文件数量超过上限（" + RESOURCE_MAX_COUNT + " 个）");
        }
        Path dir = skillDirOf(skillId);
        List<Map.Entry<Path, String>> pending = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            String rel = normalizeResourcePath(e.getKey());
            if (e.getValue() != null && e.getValue().length() > RESOURCE_MAX_CHARS) {
                throw ApiException.badRequest("辅助文件「" + rel + "」超过单文件大小上限");
            }
            Path target = dir.resolve(rel);
            pending.add(Map.entry(target, e.getValue() == null ? "" : e.getValue()));
        }
        try {
            Files.createDirectories(dir);
            for (Map.Entry<Path, String> e : pending) {
                Files.createDirectories(e.getKey().getParent());
                Files.writeString(e.getKey(), e.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("写入辅助文件失败：" + ex.getMessage(), ex);
        }
    }

    /** 供安装工具在审批前预检辅助文件路径（不落盘），非法直接抛 ApiException */
    public void validateResourcePath(String raw) {
        normalizeResourcePath(raw);
    }

    /** 校验并规范化辅助文件相对路径：反斜杠归一为 /，拒绝穿越、绝对路径与非法字符，返回 / 分隔的相对路径 */
    private String normalizeResourcePath(String raw) {
        String p = raw == null ? "" : raw.trim().replace('\\', '/');
        if (p.isEmpty()) throw ApiException.badRequest("辅助文件路径不能为空");
        if (p.startsWith("/") || p.contains(":")) throw ApiException.badRequest("辅助文件路径必须是相对路径：" + raw);
        if (p.endsWith("/")) throw ApiException.badRequest("辅助文件路径不能以 / 结尾：" + raw);
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) {
                throw ApiException.badRequest("辅助文件路径非法：" + raw);
            }
            if (seg.matches(".*[<>\"|?*\\p{Cntrl}].*") || seg.endsWith(" ")) {
                throw ApiException.badRequest("辅助文件路径含非法字符：" + raw);
            }
            if (SKILL_FILE.equalsIgnoreCase(seg) || SCOPE_FILE.equalsIgnoreCase(seg)) {
                throw ApiException.badRequest("辅助文件不能占用保留文件名：" + raw);
            }
        }
        return p;
    }

    /** 使用范围：设为全局（所有智能体可用）或取消；文件缺失/损坏按非全局处理 */
    public void setGlobal(String skillId, boolean global) {
        Path dir = skillDirOf(skillId);
        if (!Files.isRegularFile(dir.resolve(SKILL_FILE))) throw ApiException.notFound("技能不存在");
        Path scope = dir.resolve(SCOPE_FILE);
        try {
            if (global) {
                Files.createDirectories(dir);
                Files.writeString(scope, "{\"global\":true}", StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(scope);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法写入技能使用范围：" + e.getMessage(), e);
        }
    }

    /** 目录内 scope.json 标记了全局（坏 JSON 按非全局容忍） */
    private boolean isGlobalDir(Path dir) {
        Path scope = dir.resolve(SCOPE_FILE);
        if (!Files.isRegularFile(scope)) return false;
        try {
            return Json.mapper().readTree(Files.readString(scope, StandardCharsets.UTF_8))
                    .path("global").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析失败的技能（如手改坏 frontmatter）降级为文件信息，不让仓库异常打断 CRUD */
    private AgentSkill parseQuietly(String skillId) {
        return support.loadQuietly(skillId);
    }

    public SkillDetailDto create(SkillUpsertRequest req) {
        if (req == null || req.skillId() == null || !ID_PATTERN.matcher(req.skillId().trim()).matches()) {
            throw ApiException.badRequest("技能 ID 只能包含小写字母、数字、连字符和下划线，且以字母或数字开头");
        }
        String id = req.skillId().trim();
        Path dir = support.skillsDir().resolve(id);
        if (Files.exists(dir)) throw ApiException.badRequest("技能 ID 已存在：" + id);
        writeSkillFile(dir, req);
        return get(id);
    }

    public SkillDetailDto update(String skillId, SkillUpsertRequest req) {
        Path dir = skillDirOf(skillId);
        if (!Files.isRegularFile(dir.resolve(SKILL_FILE))) throw ApiException.notFound("技能不存在");
        writeSkillFile(dir, req);
        return get(skillId);
    }

    public void delete(String skillId) {
        Path dir = skillDirOf(skillId);
        if (!Files.isRegularFile(dir.resolve(SKILL_FILE))) throw ApiException.notFound("技能不存在");
        List<String> bound = support.boundAgentNames(skillId);
        if (!bound.isEmpty()) {
            throw ApiException.badRequest("技能正被智能体使用：「" + String.join("、", bound) + "」，请先在智能体里取消勾选");
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new IllegalStateException("删除技能文件失败：" + p, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("无法删除技能目录：" + e.getMessage(), e);
        }
    }

    private void writeSkillFile(Path dir, SkillUpsertRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("技能名称不能为空");
        }
        // 框架解析器要求 frontmatter 必须含非空 name/description，空描述的技能会被整体跳过、无法注入
        if (req.description() == null || req.description().isBlank()) {
            throw ApiException.badRequest("技能描述不能为空：智能体靠它判断是否加载该技能");
        }
        String name = req.name().trim();
        String description = req.description().replace("\n", " ").trim();
        // 框架以 frontmatter name 为技能身份（过滤/load_skill 都按 name 匹配），重名会让绑定串技能
        String editingId = dir.getFileName().toString();
        for (SkillSummaryDto other : list()) {
            if (!other.skillId().equals(editingId) && other.name().equals(name)) {
                throw ApiException.badRequest("技能名称「" + name + "」已被「" + other.skillId() + "」使用，请换一个名字");
            }
        }
        String content = req.content() == null ? "" : req.content().replace("\r\n", "\n");
        String file = "---\nname: " + yaml(name) + "\ndescription: " + yaml(description) + "\n---\n\n" + content;
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(SKILL_FILE), file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入技能文件：" + e.getMessage(), e);
        }
    }

    /** YAML 双引号转义（解析走框架的 snakeyaml，标准转义即可） */
    private String yaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 剥掉开头的一层或多层 frontmatter，只回传正文（写入时由 writeSkillFile 统一拼接）。
     * 历史版本曾在回显时带出 frontmatter、保存时再拼一层导致叠加，循环剥离可自愈旧文件。
     */
    private static final Pattern FRONTMATTER_HEAD = Pattern.compile("\\A---\\r?\\n.*?\\r?\\n---(?:\\r?\\n|\\z)", Pattern.DOTALL);

    private String stripFrontmatter(String raw) {
        String content = raw == null ? "" : raw.replace("\r\n", "\n");
        Matcher m = FRONTMATTER_HEAD.matcher(content);
        while (m.lookingAt()) {
            // 层与层之间隔着空行，必须先去掉行首空白，否则 \A 锚点匹配不到下一层的 ---
            content = content.substring(m.end()).stripLeading();
            m.reset(content);
        }
        return content;
    }

    /** 统一的 ID 校验 + 目录解析（防路径穿越：ID 含 ../ 等直接拒绝） */
    private Path skillDirOf(String skillId) {
        if (skillId == null || !ID_PATTERN.matcher(skillId).matches()) {
            throw ApiException.badRequest("非法的技能 ID");
        }
        return support.skillsDir().resolve(skillId);
    }

    private Path skillFileOf(String skillId) {
        return skillDirOf(skillId).resolve(SKILL_FILE);
    }

    private String readSafely(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取技能文件：" + e.getMessage(), e);
        }
    }

    private Long mtimeOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return null;
        }
    }
}
