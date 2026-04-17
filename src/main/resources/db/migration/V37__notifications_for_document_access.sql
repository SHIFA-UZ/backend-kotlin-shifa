-- Allow notifications targeting a doctor (e.g. document access request).
ALTER TABLE notifications ADD COLUMN doctor_id BIGINT NULL REFERENCES doctor_profiles(id);
ALTER TABLE notifications ADD COLUMN document_access_request_id BIGINT NULL;
-- Make patient_id nullable so we can target doctor-only notifications
ALTER TABLE notifications ALTER COLUMN patient_id DROP NOT NULL;

-- Add FK for document_access_request_id after document_access_requests exists (V36)
ALTER TABLE notifications
    ADD CONSTRAINT fk_notification_document_access_request
    FOREIGN KEY (document_access_request_id) REFERENCES document_access_requests(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_notifications_doctor_id ON notifications(doctor_id);
CREATE INDEX IF NOT EXISTS idx_notifications_document_access_request ON notifications(document_access_request_id);
