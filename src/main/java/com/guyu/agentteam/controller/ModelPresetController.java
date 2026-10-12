package com.guyu.agentteam.controller;

import com.guyu.agentteam.dto.ModelPresetDto;
import com.guyu.agentteam.dto.ModelPresetTestRequest;
import com.guyu.agentteam.dto.ModelPresetTestResult;
import com.guyu.agentteam.dto.ModelPresetUpsertRequest;
import com.guyu.agentteam.dto.PresetApplyAgentsRequest;
import com.guyu.agentteam.dto.PresetApplyResult;
import com.guyu.agentteam.service.ModelPresetService;
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
@RequestMapping("/api/model-presets")
public class ModelPresetController {

    private final ModelPresetService presetService;

    public ModelPresetController(ModelPresetService presetService) {
        this.presetService = presetService;
    }

    @GetMapping
    public List<ModelPresetDto> list() {
        return presetService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelPresetDto create(@RequestBody ModelPresetUpsertRequest req) {
        return presetService.create(req);
    }

    @PutMapping("/{id}")
    public ModelPresetDto update(@PathVariable String id, @RequestBody ModelPresetUpsertRequest req) {
        return presetService.update(id, req);
    }

    /** 测试连接：用表单当前值（可未保存）发一条真实消息，仅支持对话预设 */
    @PostMapping("/test")
    public ModelPresetTestResult test(@RequestBody ModelPresetTestRequest req) {
        return presetService.test(req);
    }

    /** 批量应用到智能体：对话预设写 presetId，文生图预设写 imagePresetId */
    @PostMapping("/{id}/apply-agents")
    public PresetApplyResult applyAgents(@PathVariable String id, @RequestBody PresetApplyAgentsRequest req) {
        return presetService.applyToAgents(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        presetService.delete(id);
    }
}
