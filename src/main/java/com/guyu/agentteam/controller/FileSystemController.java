package com.guyu.agentteam.controller;

import com.guyu.agentteam.service.FileBrowseService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/** 图片内容读取：前端气泡缩略图数据源（只读）。文件/目录选择由桌面壳原生对话框完成 */
@RestController
@RequestMapping("/api/fs")
public class FileSystemController {

    private final FileBrowseService browse;

    public FileSystemController(FileBrowseService browse) {
        this.browse = browse;
    }

    /** 按路径返回图片内容，前端气泡用 <img src="/api/fs/content?path=..."> 展示 */
    @GetMapping("/content")
    public ResponseEntity<byte[]> content(@RequestParam String path) {
        FileBrowseService.ImageContent c = browse.imageContent(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.mediaType()))
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
                .body(c.data());
    }
}
