package com.guyu.agentteam.config;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 版本化数据库迁移：按版本号顺序执行 classpath:db/migration/V{n}__xxx.sql 中未应用的脚本，
 * 记录于 _migration 表。私有化部署升级无需手动执行 SQL。
 * 脚本约束：语句以分号结尾；注释行以 -- 开头；字符串值内不要包含分号。
 */
public final class DatabaseMigrator {

    private DatabaseMigrator() {
    }

    public static void migrate(DataSource dataSource) {
        List<Resource> scripts = new ArrayList<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql")) {
                if (versionOf(resource.getFilename()) > 0) {
                    scripts.add(resource);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取数据库迁移脚本失败", e);
        }
        if (scripts.isEmpty()) {
            return;
        }
        scripts.sort(Comparator.comparingInt(r -> versionOf(r.getFilename())));

        try (Connection conn = dataSource.getConnection()) {
            ensureHistoryTable(conn);
            for (Resource script : scripts) {
                if (!applied(conn, versionOf(script.getFilename()))) {
                    apply(conn, script);
                }
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException("数据库迁移执行失败", e);
        }
    }

    private static void ensureHistoryTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS _migration ("
                    + "version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at INTEGER NOT NULL)");
        }
    }

    private static boolean applied(Connection conn, int version) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM _migration WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void apply(Connection conn, Resource script) throws Exception {
        String filename = script.getFilename();
        String sql = stripComments(script.getContentAsString(StandardCharsets.UTF_8));
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            for (String statement : sql.split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement.trim());
                }
            }
            PreparedStatement insert = conn.prepareStatement("INSERT INTO _migration(version, name, applied_at) VALUES(?, ?, ?)");
            insert.setInt(1, versionOf(filename));
            insert.setString(2, filename);
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw new IllegalStateException("数据库迁移失败：" + filename + "（" + e.getMessage() + "）", e);
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.stripLeading().startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static int versionOf(String filename) {
        if (filename == null) {
            return -1;
        }
        Matcher m = Pattern.compile("V(\\d+)__").matcher(filename);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
