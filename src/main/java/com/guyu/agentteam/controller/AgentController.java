package com.guyu.agentteam.controller;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.dto.AgentDto;
import com.guyu.agentteam.dto.AgentMemoryDto;
import com.guyu.agentteam.dto.AgentUpsertRequest;
import com.guyu.agentteam.service.AgentMemoryService;
import com.guyu.agentteam.service.AgentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;
    private final AgentMemoryService memoryService;

    public AgentController(AgentService agentService, AgentMemoryService memoryService) {
        this.agentService = agentService;
        this.memoryService = memoryService;
    }

    @GetMapping
    public List<AgentDto> list() {
        return agentService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentDto create(@RequestBody AgentUpsertRequest req) {
        return agentService.create(req);
    }

    @PutMapping("/{id}")
    public AgentDto update(@PathVariable String id, @RequestBody AgentUpsertRequest req) {
        return agentService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        agentService.delete(id);
    }

    /** 智能体的长期记忆（由智能体在对话中自行记录），最新在前 */
    @GetMapping("/{id}/memories")
    public List<AgentMemoryDto> memories(@PathVariable String id) {
        return memoryService.list(id);
    }

    @DeleteMapping("/{id}/memories")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearMemories(@PathVariable String id) {
        memoryService.clear(id);
    }
}
