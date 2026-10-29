-- Indexes for the three hottest list queries (roadmap Phase 6, "Indexes"): the per-user notification list
-- and the conversation message list are read newest-first and filtered by owner. Tables are small today,
-- so plain CREATE INDEX (no CONCURRENTLY) runs inside the migration transaction in well under a second.
CREATE INDEX IF NOT EXISTS idx_notifications_patient_created ON notifications (patient_id, created_at DESC) WHERE patient_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_notifications_doctor_created  ON notifications (doctor_id, created_at DESC) WHERE doctor_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_messages_conversation_created ON messages (conversation_id, created_at DESC);
