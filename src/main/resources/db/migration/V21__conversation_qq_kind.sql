-- QQ 会话类型（c2c=私聊 | group=群聊）：列表里以「QQ · 尾8」「QQ群 · 尾8」区分不同会话。
-- 群会话的对端短标识同步修正为群 openid 尾 8 位——此前误存群内成员 openid，
-- 而 QQ 平台成员 openid 与该用户私聊 openid 相同，导致私聊/群两个会话显示重名。
ALTER TABLE conversations ADD COLUMN qq_kind TEXT;

-- 活跃会话：按绑定键前缀判定类型，群会话 peer 取 sender_key（g:<群openid>）尾 8 位
UPDATE conversations SET
  qq_kind = 'group',
  qq_peer = (SELECT substr(b.sender_key, -8) FROM qq_bindings b
             WHERE b.conversation_id = conversations.id AND b.sender_key LIKE 'g:%')
WHERE channel = 'qq'
  AND EXISTS (SELECT 1 FROM qq_bindings b
              WHERE b.conversation_id = conversations.id AND b.sender_key LIKE 'g:%');

-- 已归档会话（绑定已迁走）：按旧内部名兜底识别群会话
UPDATE conversations SET qq_kind = 'group'
WHERE channel = 'qq' AND qq_kind IS NULL AND name LIKE 'QQ群%';

-- 其余 QQ 会话（私聊）peer 本就是用户 openid 尾 8 位，无需修正
UPDATE conversations SET qq_kind = 'c2c'
WHERE channel = 'qq' AND qq_kind IS NULL;
