-- =========================
-- V55__patient_account_deletion.sql
-- Patient self-service account deletion: challenges + deletion metadata
-- =========================

-- 1) Deletion metadata on users (auth authority)
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS account_status TEXT NOT NULL DEFAULT 'ACTIVE'
    CHECK (account_status IN ('ACTIVE', 'PENDING_DELETION', 'DELETED')),
  ADD COLUMN IF NOT EXISTS deletion_requested_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS phone_original_hash TEXT,
  ADD COLUMN IF NOT EXISTS email_original_hash TEXT;

-- Helpful index for admin/audit/reporting
CREATE INDEX IF NOT EXISTS idx_users_account_status ON users(account_status);

-- 2) One-time deletion challenges (replay protection for Firebase OTP-based confirm)
CREATE TABLE IF NOT EXISTS account_delete_challenges (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  challenge_id  UUID NOT NULL DEFAULT gen_random_uuid(),
  expires_at    TIMESTAMPTZ NOT NULL,
  used          BOOLEAN NOT NULL DEFAULT FALSE,
  attempt_count INT NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  used_at       TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_account_delete_challenges_challenge_id
  ON account_delete_challenges(challenge_id);

CREATE INDEX IF NOT EXISTS idx_account_delete_challenges_user_active
  ON account_delete_challenges(user_id, used, expires_at);

