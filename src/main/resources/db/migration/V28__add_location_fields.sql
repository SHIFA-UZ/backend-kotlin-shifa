-- Add latitude and longitude fields to doctor_profiles
ALTER TABLE doctor_profiles ADD COLUMN latitude DOUBLE PRECISION;
ALTER TABLE doctor_profiles ADD COLUMN longitude DOUBLE PRECISION;

-- Add latitude and longitude fields to patient_profiles
ALTER TABLE patient_profiles ADD COLUMN latitude DOUBLE PRECISION;
ALTER TABLE patient_profiles ADD COLUMN longitude DOUBLE PRECISION;

-- Add indexes for location-based queries
CREATE INDEX idx_doctor_profiles_location ON doctor_profiles(latitude, longitude);
CREATE INDEX idx_patient_profiles_location ON patient_profiles(latitude, longitude);
