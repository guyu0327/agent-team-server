-- V7：定时任务：任务表、会话类别（chat=普通聊天 | task=定时任务线程/群）、消息任务标注
CREATE TABLE scheduled_tasks (
    id               TEXT PRIMARY KEY,
    conversation_id  TEXT NOT NULL,
    agent_id         TEXT NOT NULL,
    name             TEXT NOT NULL,
    content          TEXT NOT NULL,
    kind             TEXT NOT NULL,
    run_at           INTEGER,
    time_of_day      TEXT,
    days_of_week     TEXT,
    interval_minutes INTEGER,
    next_run_at      INTEGER,
    last_run_at      INTEGER,
    status           TEXT NOT NULL DEFAULT 'active',
    created_at       INTEGER NOT NULL
);

CREATE INDEX idx_scheduled_tasks_conversation ON scheduled_tasks(conversation_id);
CREATE INDEX idx_scheduled_tasks_agent ON scheduled_tasks(agent_id);
CREATE INDEX idx_scheduled_tasks_status ON scheduled_tasks(status);

ALTER TABLE conversations ADD COLUMN category TEXT NOT NULL DEFAULT 'chat';
ALTER TABLE messages ADD COLUMN task_id TEXT;
ALTER TABLE messages ADD COLUMN task_name TEXT;
