-- =========================
-- V14__admin_panel_tables.sql
-- Admin Panel: Admin users, enhanced invitation keys, audit logs
-- =========================

-- 1) Admin Profiles (extends User with admin-specific data)
CREATE TABLE IF NOT EXISTS admin_profiles (
  id                 BIGSERIAL PRIMARY KEY,
  user_id            BIGINT UNIQUE NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  first_name         TEXT NOT NULL,
  last_name          TEXT NOT NULL,
  admin_level        TEXT NOT NULL DEFAULT 'ADMIN', -- ADMIN | SUPER_ADMIN | READ_ONLY
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 2) Enhanced Invitation Keys (with expiration, email, scoped permissions)
ALTER TABLE invitation_keys 
  ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS created_by_user_id BIGINT REFERENCES users(id),
  ADD COLUMN IF NOT EXISTS email_sent_to TEXT,
  ADD COLUMN IF NOT EXISTS email_sent_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS purpose TEXT DEFAULT 'DOCTOR_ONBOARDING', -- DOCTOR_ONBOARDING | PATIENT_INVITE | etc.
  ADD COLUMN IF NOT EXISTS notes TEXT;

-- 3) Audit Logs (tracks all admin actions)
CREATE TABLE IF NOT EXISTS audit_logs (
  id                 BIGSERIAL PRIMARY KEY,
  admin_user_id      BIGINT NOT NULL REFERENCES users(id),
  action_type        TEXT NOT NULL, -- USER_CREATED | USER_DEACTIVATED | TOKEN_GENERATED | PASSWORD_RESET | etc.
  entity_type        TEXT NOT NULL, -- USER | INVITATION_KEY | DOCTOR_PROFILE | PATIENT_PROFILE | etc.
  entity_id          BIGINT,
  details            JSONB, -- Flexible JSON for action-specific data
  ip_address         TEXT,
  user_agent         TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_admin_time 
  ON audit_logs (admin_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_entity 
  ON audit_logs (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action 
  ON audit_logs (action_type, created_at DESC);

-- 4) User Activity Logs (login, password reset, etc.)
CREATE TABLE IF NOT EXISTS user_activity_logs (
  id                 BIGSERIAL PRIMARY KEY,
  user_id            BIGINT NOT NULL REFERENCES users(id),
  activity_type      TEXT NOT NULL, -- LOGIN | LOGOUT | PASSWORD_RESET | PASSWORD_CHANGED | etc.
  ip_address         TEXT,
  user_agent         TEXT,
  success            BOOLEAN NOT NULL DEFAULT TRUE,
  failure_reason     TEXT, -- Only set when success = false
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_user_activity_user_time 
  ON user_activity_logs (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_activity_type 
  ON user_activity_logs (activity_type, created_at DESC);

-- 5) User Sessions (for force logout capability)
CREATE TABLE IF NOT EXISTS user_sessions (
  id                 BIGSERIAL PRIMARY KEY,
  user_id            BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_jti          TEXT UNIQUE, -- JWT ID claim for token revocation
  ip_address         TEXT,
  user_agent         TEXT,
  expires_at         TIMESTAMPTZ NOT NULL,
  revoked            BOOLEAN NOT NULL DEFAULT FALSE,
  revoked_at         TIMESTAMPTZ,
  revoked_by_user_id BIGINT REFERENCES users(id), -- Admin who revoked it
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_user_sessions_user 
  ON user_sessions (user_id, revoked, expires_at);
CREATE INDEX IF NOT EXISTS idx_user_sessions_token 
  ON user_sessions (token_jti) WHERE token_jti IS NOT NULL;

-- 6) System Configuration (feature flags, password policy, etc.)
CREATE TABLE IF NOT EXISTS system_config (
  id                 BIGSERIAL PRIMARY KEY,
  config_key         TEXT UNIQUE NOT NULL,
  config_value       TEXT NOT NULL,
  description        TEXT,
  updated_by_user_id BIGINT REFERENCES users(id),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed default system config
INSERT INTO system_config (config_key, config_value, description) VALUES
  ('token_expiration_days', '30', 'Default expiration days for invitation tokens'),
  ('password_min_length', '8', 'Minimum password length'),
  ('password_require_uppercase', 'true', 'Require uppercase letters in passwords'),
  ('password_require_lowercase', 'true', 'Require lowercase letters in passwords'),
  ('password_require_numbers', 'true', 'Require numbers in passwords'),
  ('maintenance_mode', 'false', 'Enable/disable maintenance mode'),
  ('max_failed_login_attempts', '5', 'Maximum failed login attempts before lockout')
ON CONFLICT (config_key) DO NOTHING;

-- 7) Add last_login tracking to users table
ALTER TABLE users 
  ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS failed_login_attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;
