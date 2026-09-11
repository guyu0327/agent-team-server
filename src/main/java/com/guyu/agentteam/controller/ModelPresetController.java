package com.guyu.agentteam.controller;

import com.guyu.agentteam.dto.ModelPresetDto;
import com.guyu.agentteam.dto.ModelPresetUpsertRequest;
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

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        presetService.delete(id);
    }
}
