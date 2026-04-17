-- Phase 0/1/2 foundation for ICD-10 hybrid diagnosis system.

-- 1) Extend patient_forms with optional structured diagnosis fields (backward compatible).
ALTER TABLE patient_forms
  ADD COLUMN IF NOT EXISTS diagnosis_code VARCHAR(16),
  ADD COLUMN IF NOT EXISTS diagnosis_display TEXT,
  ADD COLUMN IF NOT EXISTS diagnosis_system VARCHAR(16);

-- 2) Create ICD-10 catalog table.
CREATE TABLE IF NOT EXISTS icd10_codes (
  code VARCHAR(16) PRIMARY KEY,
  title TEXT NOT NULL,
  title_ru TEXT NULL,
  keywords TEXT NULL
);

-- Indexes for fast search.
CREATE INDEX IF NOT EXISTS idx_icd10_code ON icd10_codes (code);
CREATE INDEX IF NOT EXISTS idx_icd10_title ON icd10_codes (title);

-- 3) Extend ai_draft_notes with optional suggestions payload.
ALTER TABLE ai_draft_notes
  ADD COLUMN IF NOT EXISTS icd_suggestions_json TEXT;

