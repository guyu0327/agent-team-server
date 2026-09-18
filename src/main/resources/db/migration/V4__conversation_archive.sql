-- V4：会话归档（历史会话）：archived_at 非空表示已归档，活跃查询一律排除
ALTER TABLE conversations ADD COLUMN archived_at INTEGER;
CREATE INDEX IF NOT EXISTS idx_conversations_user_archived ON conversations(user_id, archived_at);
