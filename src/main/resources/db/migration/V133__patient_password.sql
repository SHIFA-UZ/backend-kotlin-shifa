-- An account that is both a patient and a doctor (or clinic staff) keeps one password per app. Null: the patient
-- app uses the shared password_hash, which is every account until one side of such an account changes its password.
ALTER TABLE users ADD COLUMN IF NOT EXISTS patient_password_hash VARCHAR(255);
