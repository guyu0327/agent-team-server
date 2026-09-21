CREATE TABLE wechat_bindings (
    sender_user_id  TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    created_at      INTEGER NOT NULL
);

CREATE INDEX idx_wechat_bindings_conv ON wechat_bindings(conversation_id);
