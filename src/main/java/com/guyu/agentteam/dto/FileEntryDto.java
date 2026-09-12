package com.guyu.agentteam.dto;

/** 文件浏览条目：size 仅文件有值（字节） */
public record FileEntryDto(String name, String path, boolean directory, Long size) {
}
