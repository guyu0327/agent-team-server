package com.guyu.agentteam.controller;

import com.guyu.agentteam.dto.WorkspaceSettingsDto;
import com.guyu.agentteam.dto.WorkspaceSettingsRequest;
import com.guyu.agentteam.service.tool.WorkspaceFileTools;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final WorkspaceFileTools workspace;

    public SettingsController(WorkspaceFileTools workspace) {
        this.workspace = workspace;
    }

    @GetMapping("/workspace")
    public WorkspaceSettingsDto workspace() {
        return new WorkspaceSettingsDto(workspace.primaryRoot(), workspace.extraRoots());
    }

    @PutMapping("/workspace")
    public WorkspaceSettingsDto updateWorkspace(@RequestBody WorkspaceSettingsRequest req) {
        workspace.updateSandbox(req.root(), req.extraDirs());
        return new WorkspaceSettingsDto(workspace.primaryRoot(), workspace.extraRoots());
    }
}
