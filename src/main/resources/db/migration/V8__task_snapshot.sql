-- V8：任务归档快照：删除定时任务归档消息时，把任务配置（触发方式等）存进归档会话，供「恢复任务」重建
ALTER TABLE conversations ADD COLUMN task_snapshot TEXT;
