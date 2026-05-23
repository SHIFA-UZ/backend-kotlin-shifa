-- Clinic-scoped receptionist invitations + denormalized identity for CLINIC_STAFF users
ALTER TABLE invitation_keys
    ADD COLUMN IF NOT EXISTS clinic_id BIGINT REFERENCES clinics(id),
    ADD COLUMN IF NOT EXISTS membership_role VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_invitation_keys_clinic_purpose
    ON invitation_keys (clinic_id, purpose)
    WHERE consumed = FALSE;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS staff_first_name TEXT,
    ADD COLUMN IF NOT EXISTS staff_last_name TEXT,
    ADD COLUMN IF NOT EXISTS staff_time_zone TEXT,
    ADD COLUMN IF NOT EXISTS staff_photo_url TEXT;
