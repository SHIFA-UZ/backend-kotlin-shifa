ALTER TABLE doctor_profiles
    ADD COLUMN IF NOT EXISTS sms_reminders_allowed BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS doctor_sms_usage (
    id BIGSERIAL PRIMARY KEY,
    doctor_id BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    patient_id BIGINT REFERENCES patient_profiles(id) ON DELETE SET NULL,
    appointment_id BIGINT REFERENCES appointments(id) ON DELETE SET NULL,
    cost_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'UZS',
    devsms_sms_id VARCHAR(64),
    sent_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_doctor_sms_usage_doctor_sent_at
    ON doctor_sms_usage (doctor_id, sent_at);
