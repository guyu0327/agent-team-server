package com.guyu.agentteam.dto;

import java.util.List;

/**
 * 文件浏览结果：path 为空表示盘符根视图（parent 为 null）；
 * truncated 表示条目超出单次返回上限
 */
public record FileListingDto(String path, String name, String parent, boolean truncated,
                             List<FileEntryDto> items) {
}
