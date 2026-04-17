-- Add photo_url column to patient_profiles table
-- This column was expected by the PatientProfile entity but was missing from the schema
ALTER TABLE patient_profiles
ADD COLUMN IF NOT EXISTS photo_url TEXT;

-- Optionally migrate data from avatar_url to photo_url if avatar_url has values and photo_url is null
UPDATE patient_profiles
SET photo_url = avatar_url
WHERE avatar_url IS NOT NULL AND photo_url IS NULL;
