package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.dto.FileEntryDto;
import com.guyu.agentteam.dto.FileListingDto;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * 供前端文件选择弹窗使用的服务端目录浏览（只读）。
 * 前后端同机部署，浏览器拿不到绝对路径，由后端列出目录、前端选中后拿到真实绝对路径。
 */
@Service
public class FileBrowseService {

    /** 单次返回条目上限，避免 node_modules 之类的巨型目录拖垮弹窗 */
    private static final int MAX_ENTRIES = 2000;

    /** path 为空时返回盘符根视图 */
    public FileListingDto list(String path) {
        if (path == null || path.isBlank()) {
            List<FileEntryDto> roots = StreamSupport
                    .stream(java.nio.file.FileSystems.getDefault().getRootDirectories().spliterator(), false)
                    .map(root -> new FileEntryDto(root.toString(), root.toString(), true, null))
                    .sorted(Comparator.comparing(FileEntryDto::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            return new FileListingDto("", "此电脑", null, false, roots);
        }
        Path p;
        try {
            p = Paths.get(path.trim()).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw ApiException.badRequest("路径无效：" + path);
        }
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.badRequest("路径不存在：" + p);
        }
        if (!Files.isDirectory(p)) {
            throw ApiException.badRequest("不是目录：" + p);
        }
        List<FileEntryDto> items = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> s = Files.list(p)) {
            for (Path child : s.sorted(Comparator
                    .comparing((Path x) -> !Files.isDirectory(x))
                    .thenComparing(x -> x.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList()) {
                if (items.size() >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                boolean dir = Files.isDirectory(child);
                items.add(new FileEntryDto(
                        child.getFileName().toString(),
                        child.toAbsolutePath().normalize().toString(),
                        dir,
                        dir ? null : sizeOf(child)));
            }
        } catch (IOException e) {
            throw ApiException.badRequest("无法读取目录：" + e.getMessage());
        }
        Path parent = p.getParent();
        String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
        return new FileListingDto(p.toString(), name, parent == null ? "" : parent.toString(), truncated, items);
    }

    private Long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return null;
        }
    }
}
