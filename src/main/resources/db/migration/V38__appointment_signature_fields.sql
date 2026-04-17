-- Add digital signature fields to appointments (MVP)
ALTER TABLE appointments
  ADD COLUMN IF NOT EXISTS signature_requested BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS patient_signature_image TEXT,
  ADD COLUMN IF NOT EXISTS patient_signed_at TIMESTAMPTZ;
