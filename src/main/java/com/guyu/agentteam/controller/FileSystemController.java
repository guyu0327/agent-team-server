package com.guyu.agentteam.controller;

import com.guyu.agentteam.dto.FileListingDto;
import com.guyu.agentteam.service.FileBrowseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
