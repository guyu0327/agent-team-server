package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.Images;
import com.guyu.agentteam.dto.FileGrantDto;
import com.guyu.agentteam.entity.ConversationFileGrant;
import com.guyu.agentteam.entity.ConversationFileGrantId;
import com.guyu.agentteam.repository.ConversationFileGrantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** 会话级文件授权：聊天中附加的路径登记进来，智能体的文件工具即可读写 */
@Service
public class FileGrantService {

    private final ConversationFileGrantRepository grants;

    public FileGrantService(ConversationFileGrantRepository grants) {
        this.grants = grants;
    }

    public List<FileGrantDto> list(String conversationId) {
        return grants.findByConversationIdOrderByGrantedAtAsc(conversationId).stream()
                .map(FileGrantDto::from)
                .toList();
    }

    /** 批量登记已校验的授权 */
    @Transactional
    public void registerAll(List<ConversationFileGrant> validated) {
        grants.saveAll(validated);
    }

    /** 把来源会话的授权复制到目标会话（编排建群时沿用发起会话的授权范围） */
    @Transactional
    public void copyGrants(String sourceConversationId, String targetConversationId) {
        List<ConversationFileGrant> source = grants.findByConversationIdOrderByGrantedAtAsc(sourceConversationId);
        if (source.isEmpty()) return;
        long now = System.currentTimeMillis();
        grants.saveAll(source.stream()
                .map(g -> new ConversationFileGrant(targetConversationId, g.getPath(), g.getType(), g.getName(), now))
                .toList());
    }

    /** 撤销会话授权：只解析路径不做存在性校验，文件已删除的授权也要能撤销 */
    @Transactional
    public boolean revoke(String conversationId, String path) {
        ConversationFileGrantId id = new ConversationFileGrantId(conversationId, normalizePath(path).toString());
        if (!grants.existsById(id)) return false;
        grants.deleteById(id);
        return true;
    }

    /** 校验并构造授权记录（不落库）：登记新授权时要求路径存在 */
    public ConversationFileGrant validate(String conversationId, String raw, long now) {
        Path p = normalizePath(raw);
        if (!Files.exists(p)) {
            throw ApiException.badRequest("路径不存在：" + p);
        }
        String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
        String type = Files.isDirectory(p)
                ? ConversationFileGrant.TYPE_DIR
                : Images.isImage(name) ? ConversationFileGrant.TYPE_IMAGE
                : ConversationFileGrant.TYPE_FILE;
        return new ConversationFileGrant(conversationId, p.toString(), type, name, now);
    }

    /** 仅解析路径（非空 + 绝对路径 + normalize），不校验存在性 */
    private Path normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("路径不能为空");
        }
        Path p;
        try {
            p = Paths.get(raw.trim());
        } catch (Exception e) {
            throw ApiException.badRequest("路径无效：" + raw);
        }
        if (!p.isAbsolute()) {
            throw ApiException.badRequest("必须是绝对路径：" + raw);
        }
        return p.normalize();
    }
}
