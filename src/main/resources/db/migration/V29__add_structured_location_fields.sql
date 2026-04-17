-- Add structured location fields to doctor_profiles
ALTER TABLE doctor_profiles 
    ADD COLUMN location_country VARCHAR(100),
    ADD COLUMN location_region VARCHAR(100),
    ADD COLUMN location_district VARCHAR(100),
    ADD COLUMN location_city VARCHAR(100),
    ADD COLUMN location_postal_code VARCHAR(20),
    ADD COLUMN location_street_address VARCHAR(255);

-- Add structured location fields to patient_profiles
ALTER TABLE patient_profiles 
    ADD COLUMN location_country VARCHAR(100),
    ADD COLUMN location_region VARCHAR(100),
    ADD COLUMN location_district VARCHAR(100),
    ADD COLUMN location_city VARCHAR(100),
    ADD COLUMN location_postal_code VARCHAR(20),
    ADD COLUMN location_street_address VARCHAR(255);

-- Add indexes for location-based queries
CREATE INDEX idx_doctor_profiles_location_country ON doctor_profiles(location_country);
CREATE INDEX idx_doctor_profiles_location_region ON doctor_profiles(location_region);
CREATE INDEX idx_doctor_profiles_location_district ON doctor_profiles(location_district);
CREATE INDEX idx_doctor_profiles_location_city ON doctor_profiles(location_city);

CREATE INDEX idx_patient_profiles_location_country ON patient_profiles(location_country);
CREATE INDEX idx_patient_profiles_location_region ON patient_profiles(location_region);
CREATE INDEX idx_patient_profiles_location_district ON patient_profiles(location_district);
CREATE INDEX idx_patient_profiles_location_city ON patient_profiles(location_city);
