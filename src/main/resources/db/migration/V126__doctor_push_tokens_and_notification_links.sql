-- One push token per device for doctors (the single doctor_profiles.fcm_token stays for the Flutter build).
CREATE TABLE IF NOT EXISTS doctor_push_tokens (
    id                BIGSERIAL PRIMARY KEY,
    doctor_profile_id BIGINT       NOT NULL REFERENCES doctor_profiles (id) ON DELETE CASCADE,
    token             VARCHAR(512) NOT NULL UNIQUE,
    platform          VARCHAR(16),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_doctor_push_tokens_doctor ON doctor_push_tokens (doctor_profile_id);

-- The conversation of a chat notification and the partnership of a care-partnership one, so the inbox and the push
-- can open them directly.
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS conversation_id BIGINT;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS care_partnership_id BIGINT;
