-- V8__add_dental_chart_to_patient_forms.sql
-- Adds JSONB field to store per-tooth dental chart data for forms.

ALTER TABLE patient_forms
ADD COLUMN IF NOT EXISTS dental_chart JSONB;