package com.guyu.agentteam.controller;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.dto.WorkspaceSettingsDto;
import com.guyu.agentteam.dto.WorkspaceSettingsRequest;
import com.guyu.agentteam.dto.XfyunAsrConfigDto;
import com.guyu.agentteam.service.AsrStreamService;
import com.guyu.agentteam.service.tool.WorkspaceFileTools;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final WorkspaceFileTools workspace;
    private final AsrStreamService asrStream;
    private final DataSource dataSource;
    private final Environment env;

    public SettingsController(WorkspaceFileTools workspace, AsrStreamService asrStream,
                              DataSource dataSource, Environment env) {
        this.workspace = workspace;
        this.asrStream = asrStream;
        this.dataSource = dataSource;
        this.env = env;
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

    @GetMapping("/asr-stream")
    public XfyunAsrConfigDto asrStream() {
        return asrStream.getConfig();
    }

    @PutMapping("/asr-stream")
    public XfyunAsrConfigDto updateAsrStream(@RequestBody XfyunAsrConfigDto req) {
        return asrStream.saveConfig(req);
    }

    /**
     * 数据库快照导出：VACUUM INTO 生成一致性热备份后整文件返回。
     * 快照文件先写在库文件同目录，返回后即删。
     */
    @GetMapping("/backup/database")
    public ResponseEntity<byte[]> backupDatabase() throws IOException, SQLException {
        String url = env.getProperty("spring.datasource.url", "");
        if (!url.startsWith("jdbc:sqlite:")) {
            throw new ApiException(400, "当前数据源不是 SQLite，无法导出数据库快照");
        }
        String raw = url.substring("jdbc:sqlite:".length());
        Path dbPath = Path.of(raw);
        if (!dbPath.isAbsolute()) {
            dbPath = Path.of("").toAbsolutePath().resolve(raw).normalize();
        }
        Path snapshot = dbPath.resolveSibling("agent_team_backup_" + System.nanoTime() + ".tmp");
        try {
            try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
                st.execute("VACUUM INTO '" + snapshot.toString().replace("'", "''") + "'");
            }
            if (!Files.exists(snapshot)) {
                throw new ApiException(500, "数据库快照生成失败");
            }
            String filename = "agent-team-backup-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".db";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(Files.readAllBytes(snapshot));
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }
}
