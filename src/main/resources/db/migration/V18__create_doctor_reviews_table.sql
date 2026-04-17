-- Create doctor_reviews table
CREATE TABLE IF NOT EXISTS doctor_reviews (
    id BIGSERIAL PRIMARY KEY,
    doctor_id BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    patient_id BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    rating INTEGER NOT NULL CHECK (rating >= 1 AND rating <= 5),
    comment TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_patient_doctor_review UNIQUE (doctor_id, patient_id)
);

-- Create indexes for better query performance
CREATE INDEX IF NOT EXISTS idx_doctor_reviews_doctor_id ON doctor_reviews(doctor_id);
CREATE INDEX IF NOT EXISTS idx_doctor_reviews_patient_id ON doctor_reviews(patient_id);
CREATE INDEX IF NOT EXISTS idx_doctor_reviews_created_at ON doctor_reviews(created_at DESC);
