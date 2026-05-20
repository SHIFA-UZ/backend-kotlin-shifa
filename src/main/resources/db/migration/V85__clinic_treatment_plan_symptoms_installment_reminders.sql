-- Symptoms / plan kind on treatment plans; installment reminder dedupe; notification FK for installments

ALTER TABLE treatment_plans
    ADD COLUMN IF NOT EXISTS symptoms TEXT;

ALTER TABLE treatment_plans
    ADD COLUMN IF NOT EXISTS plan_kind VARCHAR(32) NOT NULL DEFAULT 'COMPREHENSIVE';

ALTER TABLE installment_items
    ADD COLUMN IF NOT EXISTS last_reminder_sent_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS installment_item_id BIGINT;

ALTER TABLE notifications
    ADD CONSTRAINT fk_notifications_installment_item
    FOREIGN KEY (installment_item_id) REFERENCES installment_items (id)
    ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_notifications_installment_item_id ON notifications (installment_item_id);
