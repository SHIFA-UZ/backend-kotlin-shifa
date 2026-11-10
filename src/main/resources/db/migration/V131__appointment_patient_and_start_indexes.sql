-- The patient's own appointment list, the patient overlap check on every booking and the doctor-patient
-- lookups filter on patient_id; the 60-second reminder jobs filter on start_at alone. Neither had an index
-- (only doctor_id + start_at + end_at existed), so each of them scanned the table. Small table today:
-- plain CREATE INDEX, as V120 did.
CREATE INDEX IF NOT EXISTS idx_appointments_patient_start ON appointments (patient_id, start_at DESC);
CREATE INDEX IF NOT EXISTS idx_appointments_start_at ON appointments (start_at);
