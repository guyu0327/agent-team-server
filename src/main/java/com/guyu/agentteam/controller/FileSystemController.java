package com.guyu.agentteam.controller;

import com.guyu.agentteam.dto.FileListingDto;
import com.guyu.agentteam.service.FileBrowseService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/** 服务端文件浏览：前端文件选择弹窗的数据源（只读，本机工具用） */
@RestController
@RequestMapping("/api/fs")
public class FileSystemController {

    private final FileBrowseService browse;

    public FileSystemController(FileBrowseService browse) {
        this.browse = browse;
    }

    @GetMapping("/list")
    public FileListingDto list(@RequestParam(required = false) String path) {
        return browse.list(path);
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
