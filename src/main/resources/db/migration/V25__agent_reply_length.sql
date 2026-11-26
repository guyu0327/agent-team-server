-- V25：智能体单条回复字数范围（提示词软引导，非硬截断）：min 0=不限下限，max≤0=不注入限制；
-- 默认 0–120 字，引导模型简明发言，长内容按提示词用独立一行 --- 分隔成多条消息发出
ALTER TABLE agents ADD COLUMN reply_min_chars INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agents ADD COLUMN reply_max_chars INTEGER NOT NULL DEFAULT 120;
