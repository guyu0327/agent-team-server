-- V9：任务类型（normal=普通任务，智能体独立执行 | collab=协作任务，编排者拉成员建群）与错过补发开关（默认不补发）
ALTER TABLE scheduled_tasks ADD COLUMN mode TEXT NOT NULL DEFAULT 'normal';
ALTER TABLE scheduled_tasks ADD COLUMN catch_up INTEGER NOT NULL DEFAULT 0;
