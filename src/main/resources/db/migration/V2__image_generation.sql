-- 模型预设协议类型；智能体可选绑定的图像预设
ALTER TABLE model_presets ADD COLUMN protocol TEXT NOT NULL DEFAULT 'openai-chat';
ALTER TABLE agents ADD COLUMN image_preset_id TEXT;
