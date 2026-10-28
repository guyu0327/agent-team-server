-- QQ 官方开放平台机器人通道：发送者/群 → 系统会话绑定
CREATE TABLE qq_bindings (
    sender_key      TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    created_at      INTEGER NOT NULL
);

CREATE INDEX idx_qq_bindings_conv ON qq_bindings(conversation_id);

-- QQ 会话的对端短标识（openid 尾 8 位），列表里以「QQClawBot-xxxx」区分
ALTER TABLE conversations ADD COLUMN qq_peer TEXT;
