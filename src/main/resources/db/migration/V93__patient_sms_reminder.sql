ALTER TABLE patient_profiles
    ADD COLUMN IF NOT EXISTS sms_reminder_enabled BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE appointments
    ADD COLUMN IF NOT EXISTS sms_reminder_sent_at TIMESTAMPTZ NULL;
