-- Patient signature on 025-2 (and future) forms: request + captured image
ALTER TABLE patient_forms
    ADD COLUMN IF NOT EXISTS signature_requested BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE patient_forms
    ADD COLUMN IF NOT EXISTS patient_signature_image TEXT;

ALTER TABLE patient_forms
    ADD COLUMN IF NOT EXISTS patient_signed_at TIMESTAMPTZ;

ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS patient_form_id BIGINT NULL REFERENCES patient_forms (id);

CREATE INDEX IF NOT EXISTS idx_notifications_patient_form_id ON notifications (patient_form_id);
