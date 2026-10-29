-- A remote-care task can be assigned from a visit (patient-app ask 14). Nullable: existing tasks have none.
ALTER TABLE remote_care_tasks ADD COLUMN IF NOT EXISTS appointment_id BIGINT REFERENCES appointments(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_remote_care_tasks_appointment_id ON remote_care_tasks (appointment_id) WHERE appointment_id IS NOT NULL;
