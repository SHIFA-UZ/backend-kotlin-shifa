-- Add flag to mark chat attachments separately from regular documents
ALTER TABLE patient_documents ADD COLUMN is_chat_attachment BOOLEAN NOT NULL DEFAULT FALSE;
