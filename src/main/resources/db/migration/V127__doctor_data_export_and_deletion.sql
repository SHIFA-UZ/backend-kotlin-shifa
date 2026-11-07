-- When the doctor last exported their data (GET /api/doctors/me/export is limited to once a day).
ALTER TABLE doctor_profiles ADD COLUMN IF NOT EXISTS data_exported_at TIMESTAMPTZ;
