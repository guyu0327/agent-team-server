package com.guyu.agentteam.controller;

import com.guyu.agentteam.dto.LogDto;
import com.guyu.agentteam.dto.LogPageDto;
import com.guyu.agentteam.repository.AppLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 运行日志查询：按类型与时间范围筛选，时间倒序分页（前端「设置-数据管理-查看日志」） */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AppLogRepository logs;

    public LogController(AppLogRepository logs) {
        this.logs = logs;
    }

    @GetMapping
    public LogPageDto list(@RequestParam(required = false) String type,
                           @RequestParam(required = false) Long from,
                           @RequestParam(required = false) Long to,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "50") int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int index = Math.max(page, 1) - 1;
        long fromAt = from == null ? 0L : from;
        long toAt = to == null ? Long.MAX_VALUE : to;
        List<LogDto> list = logs.search(blankToNull(type), fromAt, toAt, PageRequest.of(index, size + 1))
                .stream().map(LogDto::from).toList();
        boolean hasMore = list.size() > size;
        return new LogPageDto(hasMore ? list.subList(0, size) : list, hasMore);
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
