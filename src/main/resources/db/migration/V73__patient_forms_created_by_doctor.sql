-- Doctor who created the form (owner). Used for clinical RAG chunk visibility on 025-2.
ALTER TABLE patient_forms
    ADD COLUMN IF NOT EXISTS created_by_doctor_id BIGINT NULL REFERENCES doctor_profiles (id);

CREATE INDEX IF NOT EXISTS idx_patient_forms_created_by_doctor
    ON patient_forms (created_by_doctor_id);
