-- SMS OTP codes for patient registration and forgot-password (DevSMS)
CREATE TABLE IF NOT EXISTS sms_verification_codes (
    id          BIGSERIAL PRIMARY KEY,
    phone       TEXT        NOT NULL,
    code        TEXT        NOT NULL,
    purpose     TEXT        NOT NULL,
    verified    BOOLEAN     NOT NULL DEFAULT FALSE,
    attempts    INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '10 minutes')
);

CREATE INDEX idx_svc_phone_purpose ON sms_verification_codes(phone, purpose, verified);
CREATE INDEX idx_svc_cleanup ON sms_verification_codes(expires_at);
