package com.guyu.agentteam.controller;

import com.guyu.agentteam.dto.SkillDetailDto;
import com.guyu.agentteam.dto.SkillSummaryDto;
import com.guyu.agentteam.dto.SkillUpsertRequest;
import com.guyu.agentteam.service.SkillService;
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
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skills;

    public SkillController(SkillService skills) {
        this.skills = skills;
    }

    @GetMapping
    public List<SkillSummaryDto> list() {
        return skills.list();
    }

    @GetMapping("/{skillId}")
    public SkillDetailDto get(@PathVariable String skillId) {
        return skills.get(skillId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SkillDetailDto create(@RequestBody SkillUpsertRequest req) {
        return skills.create(req);
    }

    @PutMapping("/{skillId}")
    public SkillDetailDto update(@PathVariable String skillId, @RequestBody SkillUpsertRequest req) {
        return skills.update(skillId, req);
    }

    /** 使用范围：设为/取消全局（所有智能体可用） */
    @PutMapping("/{skillId}/scope")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setScope(@PathVariable String skillId, @RequestBody ScopeRequest req) {
        skills.setGlobal(skillId, req != null && req.global());
    }

    public record ScopeRequest(boolean global) {
    }

    @DeleteMapping("/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String skillId) {
        skills.delete(skillId);
    }
}
