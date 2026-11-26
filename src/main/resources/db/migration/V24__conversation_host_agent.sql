-- V24：编排者从智能体全局身份（agents.is_orchestrator，本版起停止读写）改为会话角色
-- 群聊按旧身份预填主持：恰一个编排者→他；多个→加入序第一个（与旧隐式行为一致）；
-- 零个→子查询为空置 NULL（=旧「无编排者全员依次回复」行为）。单聊不迁移（本版起单聊一律走编排路径）
ALTER TABLE conversations ADD COLUMN host_agent_id TEXT;

UPDATE conversations
SET host_agent_id = (
  SELECT m.agent_id
  FROM conversation_members m
  JOIN agents a ON a.id = m.agent_id
  WHERE m.conversation_id = conversations.id
    AND a.is_orchestrator = 1
  ORDER BY m.created_at ASC
  LIMIT 1
)
WHERE type = 'group';
