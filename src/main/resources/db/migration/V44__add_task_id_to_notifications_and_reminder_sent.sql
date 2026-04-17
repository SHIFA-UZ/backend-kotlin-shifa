-- Notifications: optional task_id for task-related notifications (assign, cancel, reminder)
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS task_id BIGINT;

-- Task check-ins: when we sent "5 min before" reminder (avoid duplicate FCM)
ALTER TABLE task_check_ins ADD COLUMN IF NOT EXISTS reminder_sent_at TIMESTAMP WITH TIME ZONE;
