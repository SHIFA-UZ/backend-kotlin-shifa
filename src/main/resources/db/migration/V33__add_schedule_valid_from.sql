-- Add schedule validity start date (from when) to doctor profiles
ALTER TABLE doctor_profiles
  ADD COLUMN IF NOT EXISTS schedule_valid_from DATE;
