CREATE TABLE agent_memories (
    id         TEXT PRIMARY KEY,
    agent_id   TEXT NOT NULL,
    content    TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX idx_agent_memories_agent ON agent_memories(agent_id, created_at);
