ALTER TABLE patient_profiles
    ADD COLUMN IF NOT EXISTS sms_reminder_hours_before INT NOT NULL DEFAULT 24;

ALTER TABLE patient_profiles
    ADD CONSTRAINT chk_patient_sms_reminder_hours_before
        CHECK (sms_reminder_hours_before IN (1, 24));
