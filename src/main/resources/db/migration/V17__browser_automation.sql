ALTER TABLE messages ADD COLUMN browse_activity TEXT;
ALTER TABLE scheduled_tasks ADD COLUMN auto_browser INTEGER NOT NULL DEFAULT 0;
