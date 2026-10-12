package com.guyu.agentteam.dto;

import java.util.List;

/** 预设批量应用到智能体：对话预设写 presetId、文生图预设写 imagePresetId */
public record PresetApplyAgentsRequest(List<String> agentIds) {
}
