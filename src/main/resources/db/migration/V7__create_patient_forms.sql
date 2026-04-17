-- V7__create_patient_forms.sql
-- ✅ Create patient_forms table to store structured form data
CREATE TABLE IF NOT EXISTS patient_forms (
    id BIGSERIAL PRIMARY KEY,
    patient_id BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    template_id TEXT NOT NULL,                    -- e.g., '025-2'
    date DATE NOT NULL,
    full_name TEXT NOT NULL,
    gender TEXT,
    address TEXT,
    age INTEGER,
    job TEXT,
    diagnosis TEXT,
    complaints TEXT,
    other_illnesses TEXT,
    more_details TEXT,
    visual_checkup TEXT,
    document_id BIGINT REFERENCES patient_documents(id),  -- Link to PDF document
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index for quick lookups by patient
CREATE INDEX IF NOT EXISTS idx_patient_forms_patient_id 
    ON patient_forms (patient_id, date DESC);

-- Index for template lookups
CREATE INDEX IF NOT EXISTS idx_patient_forms_template 
    ON patient_forms (template_id, patient_id);
