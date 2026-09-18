-- V3：应用运行日志表（供前端「查看日志」检索排查问题，保留 30 天）

-- ----------------------------
-- app_logs 运行日志
-- ----------------------------
CREATE TABLE IF NOT EXISTS app_logs (
  id TEXT PRIMARY KEY,
  type TEXT NOT NULL,
  conversation_id TEXT,
  agent_id TEXT,
  content TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_app_logs_created ON app_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_app_logs_type ON app_logs(type, created_at);
