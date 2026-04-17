-- V11__patient_form_header_fields.sql
-- Adds form_number (per patient + template) and doctor_clinic for PDF header rendering.

ALTER TABLE patient_forms
  ADD COLUMN IF NOT EXISTS form_number INTEGER,
  ADD COLUMN IF NOT EXISTS doctor_clinic TEXT;

-- Backfill form_number for existing rows (per patient + template).
WITH ranked AS (
  SELECT id,
         ROW_NUMBER() OVER (PARTITION BY patient_id, template_id ORDER BY date, id) AS rn
  FROM patient_forms
)
UPDATE patient_forms pf
SET form_number = ranked.rn
FROM ranked
WHERE pf.id = ranked.id AND pf.form_number IS NULL;

ALTER TABLE patient_forms
  ALTER COLUMN form_number SET DEFAULT 1;