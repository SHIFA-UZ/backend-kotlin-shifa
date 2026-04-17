-- V9__backfill_dental_chart.sql
-- Backfill dental_chart for existing rows created before V8 default.

UPDATE patient_forms
SET dental_chart = '{}'::jsonb
WHERE dental_chart IS NULL;

ALTER TABLE patient_forms
ALTER COLUMN dental_chart SET DEFAULT '{}'::jsonb;