-- Structured dental visit documentation (per-appointment): teeth → services, discount, notes.

ALTER TABLE appointments
    ADD COLUMN IF NOT EXISTS dental_documentation TEXT;
