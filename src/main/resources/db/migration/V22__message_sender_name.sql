-- QQ 通道消息记录外部发送者昵称（author.username 随事件下发），气泡与历史永久展示
ALTER TABLE messages ADD COLUMN sender_name TEXT;
