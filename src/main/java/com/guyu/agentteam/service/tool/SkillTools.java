package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.dto.SkillSummaryDto;
import com.guyu.agentteam.dto.SkillUpsertRequest;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.OperationGrant;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.service.SkillService;
import com.guyu.agentteam.service.SkillSupport;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 技能安装工具：install_skill（受控审批 + 默认自动启用到发起智能体）+ list_skills（已装技能列表，只读）。
 * 技能目录在文件沙箱外，智能体不能裸写 SKILL.md——安装必须走本工具，复用 SkillService 的
 * ID 规则/非空/重名/frontmatter 校验；启用直接追加 agent.skillIds，下一轮构建 ReActAgent 时生效。
 */
@Service
public class SkillTools {

    /** 与 SkillService 一致的目录名规则：先本地校验才能拼审批路径，防路径穿越 */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-_]{0,63}$");
    /** 审批卡片 detail 里正文截断长度，防止超长技能把卡片撑爆 */
    private static final int DETAIL_BODY_MAX = 2000;

    private final SkillService skills;
    private final SkillSupport support;
    private final AgentRepository agents;

    public SkillTools(SkillService skills, SkillSupport support, AgentRepository agents) {
        this.skills = skills;
        this.support = support;
        this.agents = agents;
    }

    /** agent 为发起安装/查询的智能体（编排协作里是被委派成员），启用绑定写它的 skillIds */
    public void register(Toolkit toolkit, Agent agent, OpRequestSink sink) {
        toolkit.registerTool(new SkillTool(skills, support, agents, agent, sink));
    }

    public static class SkillTool {

        private final SkillService skills;
        private final SkillSupport support;
        private final AgentRepository agents;
        private final Agent self;
        private final OpRequestSink sink;

        SkillTool(SkillService skills, SkillSupport support, AgentRepository agents,
                  Agent self, OpRequestSink sink) {
            this.skills = skills;
            this.support = support;
            this.agents = agents;
            this.self = self;
            this.sink = sink;
        }

        @Tool(name = "list_skills", description = "List all installed skills (including ones not yet enabled "
                + "for you). Each line shows the skill id, display name, description, how many agents have "
                + "enabled it, and whether you are among them (含你/不含你). Use this to check what is "
                + "available before installing a new skill, or to look up the skill_id needed for "
                + "enablement questions.")
        public String listSkills() {
            try {
                List<SkillSummaryDto> list = skills.list();
                if (list.isEmpty()) {
                    return "当前没有已安装的技能。";
                }
                // 同轮刚 install 过时 self 字段是旧的，启用状态以库里最新为准
                List<String> selfIds = SkillSupport.parseIds(
                        agents.findById(self.getId()).map(Agent::getSkillIds).orElse(null));
                StringBuilder sb = new StringBuilder("已安装技能（共 ").append(list.size()).append(" 个）：");
                for (SkillSummaryDto s : list) {
                    sb.append("\n- ").append(s.skillId())
                            .append(" · ").append(s.name())
                            .append(" — ").append(s.description());
                    if (s.global()) {
                        sb.append("（全局：所有智能体可用）");
                    } else if (s.agentCount() == 0) {
                        sb.append("（尚未启用到任何智能体）");
                    } else {
                        sb.append("（已启用：").append(s.agentCount()).append(" 个智能体")
                                .append(selfIds.contains(s.skillId()) ? "，含你）" : "，不含你）");
                    }
                    List<String> res = skills.listResources(s.skillId());
                    if (!res.isEmpty()) {
                        sb.append("\n  辅助文件：").append(prettyResourceList(res));
                    }
                }
                return sb.toString();
            } catch (Exception e) {
                return "读取技能列表失败：" + messageOf(e);
            }
        }

        @Tool(name = "install_skill", concurrencySafe = false, description = "Install a skill (a Markdown capability document) into the "
                + "team's skill library, and enable it for yourself by default so you can load it in the NEXT "
                + "conversation round. The user sees a confirmation card and must approve the install; if the "
                + "user declines, do NOT retry the same install. skill_id must be unique (lowercase letters, "
                + "digits, hyphen, underscore); name must be unique among installed skills; description must "
                + "be non-empty because other agents rely on it to decide whether to load the skill. "
                + "Inform the user of the result; after enabling, the skill takes effect from the next round, "
                + "not the current one.")
        public String installSkill(
                @ToolParam(name = "skill_id", required = true,
                        description = "Skill directory id: lowercase letters/digits/hyphen/underscore, "
                                + "e.g. 'anysearch'")
                String skillId,
                @ToolParam(name = "name", required = true,
                        description = "Skill display name (becomes the frontmatter name, the skill's identity; "
                                + "must not duplicate an installed skill's name)")
                String name,
                @ToolParam(name = "description", required = true,
                        description = "One-line description of what the skill is for and when to use it; "
                                + "must be non-empty")
                String description,
                @ToolParam(name = "content", required = false,
                        description = "Skill body in Markdown: steps, guidelines, examples. Optional but "
                                + "recommended")
                String content,
                @ToolParam(name = "enable", required = false,
                        description = "true (default) to also enable this skill for yourself after installing")
                Boolean enable,
                @ToolParam(name = "global", required = false,
                        description = "true to make the skill available to ALL agents (ask the user first); "
                                + "default false = only agents it is enabled for")
                Boolean global,
                @ToolParam(name = "files", required = false,
                        description = "Optional auxiliary files as a JSON object mapping relative paths to text "
                                + "content, e.g. {\"scripts/run.py\": \"print('hi')\", \"references/api.md\": \"...\"}. "
                                + "Use scripts/, references/, assets/ style folders; parent folders are created "
                                + "automatically. Keep files small and few; SKILL.md and scope.json are reserved.")
                String filesJson) {
            String id = skillId == null ? "" : skillId.trim();
            String nm = name == null ? "" : name.trim();
            String desc = description == null ? "" : description.replace("\n", " ").trim();
            String body = content == null ? "" : content;
            boolean enableIt = enable == null || enable;
            boolean globalIt = global != null && global;
            Map<String, String> files = null;
            String filesErr = null;
            if (filesJson != null && !filesJson.isBlank()) {
                try {
                    files = Json.mapper().readValue(filesJson, new TypeReference<LinkedHashMap<String, String>>() { });
                    if (files != null && files.isEmpty()) {
                        files = null;
                    }
                    if (files != null) {
                        for (String key : files.keySet()) {
                            skills.validateResourcePath(key);
                        }
                    }
                } catch (Exception e) {
                    files = null;
                    filesErr = messageOf(e);
                }
            }
            if (!ID_PATTERN.matcher(id).matches()) {
                return "安装失败：skill_id 只能包含小写字母、数字、连字符和下划线，且以字母或数字开头。";
            }
            // files 预检（JSON 形状 + 路径合法性）放在审批前：参数错误直接打回，不消耗一次用户审批
            if (filesErr != null) {
                return "安装失败：files 参数无效（需为 {\"相对路径\": \"文本内容\"} 形式的 JSON 对象）：" + filesErr;
            }
            // 重名预检放在审批前：避免用户批准了一个注定失败的安装
            try {
                for (SkillSummaryDto s : skills.list()) {
                    if (s.skillId().equals(id)) {
                        return "安装失败：技能 ID「" + id + "」已存在（名称：" + s.name()
                                + "）。如需更新内容请让用户在「技能」页编辑，或换一个 ID。";
                    }
                    if (s.name().equals(nm)) {
                        return "安装失败：技能名称「" + nm + "」已被 ID「" + s.skillId() + "」使用，请换一个名称。";
                    }
                }
            } catch (Exception ignored) {
                // 预检读列表失败不阻断，交给 create 的正式校验兜底
            }
            Path target = support.skillsDir().resolve(id).resolve("SKILL.md");
            String detail = "安装技能「" + nm + "」"
                    + (globalIt ? "（全局：所有智能体可用）" : enableIt ? "并启用到智能体「" + self.getName() + "」" : "（不启用）")
                    + "\nID：" + id + "\n描述：" + desc
                    + (files != null ? "\n辅助文件：" + prettyResourceList(files.keySet()) : "")
                    + "\n正文：\n" + truncateBody(body);
            if (sink != null && !sink.request(OperationGrant.OP_WRITE, target.toString(), detail)) {
                return "用户未批准本次安装，技能未安装。请尊重用户的决定，不要重复提交同样的安装。";
            }
            try {
                skills.create(new SkillUpsertRequest(id, nm, desc, body));
                if (globalIt) {
                    skills.setGlobal(id, true);
                }
            } catch (Exception e) {
                return "安装失败：" + messageOf(e);
            }
            if (files != null) {
                try {
                    skills.writeResources(id, files);
                } catch (Exception e) {
                    return "技能「" + nm + "」已安装，但辅助文件写入失败：" + messageOf(e)
                            + "。SKILL.md 本体不受影响，可请用户在技能目录手动补齐后重试。";
                }
            }
            if (!enableIt) {
                return "已安装技能「" + nm + "」（ID: " + id + "），尚未启用；用户可在「智能体编辑」里勾选启用。";
            }
            try {
                enableForSelf(id);
                return "已安装技能「" + nm + "」（ID: " + id + "）并启用到你（" + self.getName() + "），"
                        + "从下一轮对话起可按需加载使用。请转告用户：技能可在「技能」页查看。";
            } catch (Exception e) {
                return "技能「" + nm + "」已安装，但启用失败：" + messageOf(e)
                        + "。用户可在「智能体编辑」里手动勾选启用。";
            }
        }

        private void enableForSelf(String skillId) {
            Agent fresh = agents.findById(self.getId())
                    .orElseThrow(() -> new IllegalStateException("智能体不存在"));
            List<String> ids = new ArrayList<>();
            if (fresh.getSkillIds() != null && !fresh.getSkillIds().isBlank()) {
                Arrays.stream(fresh.getSkillIds().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).forEach(ids::add);
            }
            if (!ids.contains(skillId)) {
                ids.add(skillId);
            }
            fresh.setSkillIds(String.join(",", ids));
            agents.save(fresh);
        }

        private String truncateBody(String body) {
            String flat = body.replace("\r\n", "\n").strip();
            return flat.length() <= DETAIL_BODY_MAX ? flat
                    : flat.substring(0, DETAIL_BODY_MAX) + "\n…[正文过长已截断]";
        }

        /** 资源清单展示：排序后最多列 6 个，超出折叠为「等 N 个」 */
        private String prettyResourceList(Collection<String> paths) {
            List<String> sorted = new ArrayList<>(paths);
            Collections.sort(sorted);
            int max = 6;
            String joined = String.join("、", sorted.subList(0, Math.min(max, sorted.size())));
            return sorted.size() <= max ? joined : joined + " 等 " + sorted.size() + " 个";
        }

        private String messageOf(Exception e) {
            String msg = e.getMessage();
            return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
        }
    }
}
