-- V62: Add retry metadata fields for payment webhook operations.

ALTER TABLE payment_events
    ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_retry_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS retried_by_admin_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL;
