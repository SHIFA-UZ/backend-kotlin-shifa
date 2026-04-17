-- Persistent email OTP codes (replaces in-memory ConcurrentHashMap)
CREATE TABLE IF NOT EXISTS email_verification_codes (
    id          BIGSERIAL PRIMARY KEY,
    email       TEXT        NOT NULL,
    code        TEXT        NOT NULL,
    purpose     TEXT        NOT NULL,  -- REGISTRATION, LOGIN, FORGOT_PASSWORD, ACCOUNT_DELETION
    verified    BOOLEAN     NOT NULL DEFAULT FALSE,
    attempts    INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '10 minutes')
);

CREATE INDEX idx_evc_email_purpose ON email_verification_codes(email, purpose, verified);
CREATE INDEX idx_evc_cleanup ON email_verification_codes(expires_at);

-- Track email verification status on users
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing users with email are trusted
UPDATE users SET email_verified = TRUE WHERE email IS NOT NULL;
