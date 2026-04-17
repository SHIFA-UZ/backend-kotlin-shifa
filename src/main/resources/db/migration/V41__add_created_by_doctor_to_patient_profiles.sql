-- Patients created by a doctor from "New patient" should appear in that doctor's "my patients" list.
ALTER TABLE patient_profiles ADD COLUMN created_by_doctor_id BIGINT NULL REFERENCES doctor_profiles(id);
CREATE INDEX IF NOT EXISTS idx_patient_profiles_created_by_doctor ON patient_profiles(created_by_doctor_id);
