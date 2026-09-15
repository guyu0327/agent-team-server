-- V1：初始表结构
-- 约束：语句以分号结尾，字符串值内不要包含分号；注释行以 -- 开头

-- ----------------------------
-- agents 智能体表
-- ----------------------------
CREATE TABLE IF NOT EXISTS agents (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  name TEXT NOT NULL,
  avatar TEXT NOT NULL DEFAULT '',
  group_name TEXT NOT NULL DEFAULT '',
  description TEXT NOT NULL DEFAULT '',
  preset_id TEXT,
  system_prompt TEXT,
  temperature NUMERIC NOT NULL DEFAULT 0.7,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  is_orchestrator INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_agents_user ON agents(user_id);

-- ----------------------------
-- app_settings 应用设置
-- ----------------------------
CREATE TABLE IF NOT EXISTS app_settings (
  setting_key TEXT PRIMARY KEY,
  setting_value TEXT,
  updated_at INTEGER NOT NULL
);

-- ----------------------------
-- conversation_file_grants 会话文件授权表
-- ----------------------------
CREATE TABLE IF NOT EXISTS conversation_file_grants (
  conversation_id TEXT NOT NULL,
  path TEXT NOT NULL,
  type TEXT NOT NULL,
  name TEXT NOT NULL DEFAULT '',
  granted_at INTEGER NOT NULL,
  PRIMARY KEY (conversation_id, path)
);

-- ----------------------------
-- operation_grants 会话受控操作授权表
-- ----------------------------
CREATE TABLE IF NOT EXISTS operation_grants (
  conversation_id TEXT NOT NULL,
  op_type TEXT NOT NULL,
  granted_at INTEGER NOT NULL,
  PRIMARY KEY (conversation_id, op_type)
);

-- ----------------------------
-- conversation_members 会话成员表
-- ----------------------------
CREATE TABLE IF NOT EXISTS conversation_members (
  conversation_id TEXT NOT NULL,
  agent_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (conversation_id, agent_id)
);
CREATE INDEX IF NOT EXISTS idx_members_agent ON conversation_members(agent_id);

-- ----------------------------
-- conversations 会话表
-- ----------------------------
CREATE TABLE IF NOT EXISTS conversations (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  type TEXT NOT NULL,
  name TEXT NOT NULL DEFAULT '',
  pinned INTEGER NOT NULL DEFAULT 0,
  last_message TEXT,
  last_message_at INTEGER,
  last_read_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  chat_mode TEXT NOT NULL DEFAULT 'passive'
);
CREATE INDEX IF NOT EXISTS idx_conv_list ON conversations(user_id, pinned, last_message_at);

-- ----------------------------
-- messages 消息表
-- ----------------------------
CREATE TABLE IF NOT EXISTS messages (
  id TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL,
  sender_type TEXT NOT NULL,
  sender_id TEXT NOT NULL,
  content TEXT,
  attachments TEXT,
  type TEXT NOT NULL DEFAULT 'text',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_msg_page ON messages(conversation_id, created_at);

-- ----------------------------
-- model_presets 模型预设表
-- ----------------------------
CREATE TABLE IF NOT EXISTS model_presets (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  base_url TEXT NOT NULL,
  api_key TEXT NOT NULL DEFAULT '',
  remark TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

-- ----------------------------
-- users 用户表
-- ----------------------------
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  avatar TEXT NOT NULL DEFAULT '',
  signature TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
