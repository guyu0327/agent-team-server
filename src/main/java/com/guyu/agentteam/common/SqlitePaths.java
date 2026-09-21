package com.guyu.agentteam.common;

import java.nio.file.Path;

/** spring.datasource.url 里 SQLite 库文件路径的解析（剥 query 参数），供数据目录创建与备份导出共用 */
public final class SqlitePaths {

    private SqlitePaths() {
    }

    /** 解析出库文件的绝对规范化路径；非 jdbc:sqlite:、内存库或空路径返回 null */
    public static Path dbFile(String url) {
        if (url == null || !url.startsWith("jdbc:sqlite:")) {
            return null;
        }
        String raw = url.substring("jdbc:sqlite:".length());
        int query = raw.indexOf('?');
        if (query >= 0) {
            raw = raw.substring(0, query);
        }
        if (raw.isBlank() || raw.startsWith(":memory:")) {
            return null;
        }
        return Path.of(raw).toAbsolutePath().normalize();
    }
}
