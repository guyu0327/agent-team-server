package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.Images;
import com.guyu.agentteam.service.tool.WorkspaceFileTools;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class FileBrowseService {

    /** 可返回给前端的单图上限 */
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;

    private final WorkspaceFileTools workspace;

    public FileBrowseService(WorkspaceFileTools workspace) {
        this.workspace = workspace;
    }

    /** 读取图片文件内容，供前端气泡直接展示（仅限图片，超大图拒绝）；相对路径按主工作区根目录解析 */
    public ImageContent imageContent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("路径不能为空");
        }
        Path p;
        try {
            Path rawPath = Paths.get(raw.trim());
            if (!rawPath.isAbsolute()) {
                rawPath = Paths.get(workspace.primaryRoot()).resolve(rawPath);
            }
            p = rawPath.toAbsolutePath().normalize();
        } catch (Exception e) {
            throw ApiException.badRequest("路径无效：" + raw);
        }
        if (!Files.isRegularFile(p)) {
            throw ApiException.badRequest("文件不存在：" + p);
        }
        String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
        String mediaType = Images.mediaType(name);
        if (mediaType == null) {
            throw ApiException.badRequest("不是支持的图片文件：" + name);
        }
        try {
            if (Files.size(p) > MAX_IMAGE_BYTES) {
                throw ApiException.badRequest("图片过大（超过 20MB）：" + name);
            }
            return new ImageContent(Files.readAllBytes(p), mediaType);
        } catch (IOException e) {
            throw ApiException.badRequest("读取图片失败：" + e.getMessage());
        }
    }

    public record ImageContent(byte[] data, String mediaType) {
    }
}
