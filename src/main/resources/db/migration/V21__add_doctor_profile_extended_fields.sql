-- Add extended fields to doctor_profiles table
-- biography, services, certificates, telegram, instagram

ALTER TABLE doctor_profiles
ADD COLUMN IF NOT EXISTS biography TEXT,
ADD COLUMN IF NOT EXISTS services TEXT,
ADD COLUMN IF NOT EXISTS certificates TEXT,
ADD COLUMN IF NOT EXISTS telegram VARCHAR(255),
ADD COLUMN IF NOT EXISTS instagram VARCHAR(255);
