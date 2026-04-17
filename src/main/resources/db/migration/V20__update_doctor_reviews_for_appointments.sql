-- Update doctor_reviews table to support appointment-based reviews
-- Allow multiple reviews per doctor-patient pair, but only one per appointment

-- Add appointment_id column (nullable initially for existing reviews)
ALTER TABLE doctor_reviews 
ADD COLUMN IF NOT EXISTS appointment_id BIGINT REFERENCES appointments(id) ON DELETE CASCADE;

-- Drop the old unique constraint on (doctor_id, patient_id)
ALTER TABLE doctor_reviews 
DROP CONSTRAINT IF EXISTS unique_patient_doctor_review;

-- Add new unique constraint on appointment_id (one review per appointment)
ALTER TABLE doctor_reviews 
ADD CONSTRAINT unique_appointment_review UNIQUE (appointment_id);

-- Create index for appointment_id
CREATE INDEX IF NOT EXISTS idx_doctor_reviews_appointment_id ON doctor_reviews(appointment_id);
