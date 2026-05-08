-- V67: Admin-managed subscription tier per user.
-- Drives feature gating in the doctor and patient apps.
-- BASIC | PRO | PREMIUM. PATIENT users may only be PRO or PREMIUM (enforced in service code).
-- Default is PREMIUM so existing users keep their current functionality until admins downgrade them.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS subscription_tier VARCHAR(16) NOT NULL DEFAULT 'PREMIUM';

CREATE INDEX IF NOT EXISTS idx_users_subscription_tier
    ON users(subscription_tier);
