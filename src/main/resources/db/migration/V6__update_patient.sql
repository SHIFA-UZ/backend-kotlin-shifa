-- V3__create_patient_documents.sql
-- ✅ Create patient_documents table
CREATE TABLE IF NOT EXISTS patient_documents (
    id BIGSERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    date DATE NOT NULL,
    file_path TEXT,
    patient_id BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE
);

-- ✅ Optional: Seed sample documents for existing patients
INSERT INTO patient_documents (title, date, file_path, patient_id)
VALUES
('Blood Test', '2024-09-21', NULL, 1),
('MRI Result', '2024-08-14', NULL, 1),
('X-Ray', '2024-09-10', NULL, 2),
('ECG', '2024-07-12', NULL, 4);
