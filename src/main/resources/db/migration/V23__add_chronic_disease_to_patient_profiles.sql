ALTER TABLE patient_profiles
ADD COLUMN IF NOT EXISTS chronic_disease VARCHAR(255);
