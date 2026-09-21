-- 会话来源通道标记：wechat=iLink 微信通道会话（桌面端只读展示，聊天在微信内进行）
ALTER TABLE conversations ADD COLUMN channel TEXT;
-- 存量微信会话按旧命名规则回填，并统一改用固定显示名
UPDATE conversations SET channel = 'wechat' WHERE name LIKE '微信 · %';
UPDATE conversations SET name = '微信ClawBot' WHERE channel = 'wechat';
