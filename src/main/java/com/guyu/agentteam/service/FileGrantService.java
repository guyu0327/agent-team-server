package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
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

    /** 校验并登记授权（已存在的会刷新元数据），返回授权后的完整列表 */
    @Transactional
    public List<FileGrantDto> grant(String conversationId, List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            throw ApiException.badRequest("缺少要授权的路径");
        }
        grants.saveAll(validated(conversationId, paths, System.currentTimeMillis()));
        return list(conversationId);
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

    private List<ConversationFileGrant> validated(String conversationId, List<String> paths, long now) {
        return paths.stream()
                .map(raw -> validate(conversationId, raw, now))
                .toList();
    }

    @Transactional
    public boolean revoke(String conversationId, String path) {
        ConversationFileGrantId id = new ConversationFileGrantId(conversationId, normalize(path).toString());
        if (!grants.existsById(id)) return false;
        grants.deleteById(id);
        return true;
    }

    /** 校验并构造授权记录（不落库） */
    public ConversationFileGrant validate(String conversationId, String raw, long now) {
        Path p = normalize(raw);
        String type = Files.isDirectory(p)
                ? ConversationFileGrant.TYPE_DIR
                : ConversationFileGrant.TYPE_FILE;
        String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
        return new ConversationFileGrant(conversationId, p.toString(), type, name, now);
    }

    private Path normalize(String raw) {
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
        p = p.normalize();
        if (!Files.exists(p)) {
            throw ApiException.badRequest("路径不存在：" + p);
        }
        return p;
    }
}
