-- Notifications: optional document fields for document-access deep links (DOCUMENT_ACCESS_* types)
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS document_id BIGINT;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS document_patient_id BIGINT;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS document_title VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_notifications_document_id ON notifications(document_id) WHERE document_id IS NOT NULL;
