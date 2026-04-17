-- V10__extend_patient_forms_for_025_2.sql
-- Adds additional fields for 025-2 dental form + followups table stored as JSONB.

ALTER TABLE patient_forms
  ADD COLUMN IF NOT EXISTS occlusion TEXT,
  ADD COLUMN IF NOT EXISTS oral_cavity_condition TEXT,
  ADD COLUMN IF NOT EXISTS xray_lab_data TEXT,
  ADD COLUMN IF NOT EXISTS treatment TEXT,
  ADD COLUMN IF NOT EXISTS treatment_result TEXT,
  ADD COLUMN IF NOT EXISTS recommendations TEXT,
  ADD COLUMN IF NOT EXISTS doctor_name TEXT,
  ADD COLUMN IF NOT EXISTS followups JSONB;

-- Backfill JSON defaults
UPDATE patient_forms
SET followups = '[]'::jsonb
WHERE followups IS NULL;

ALTER TABLE patient_forms
  ALTER COLUMN followups SET DEFAULT '[]'::jsonb;