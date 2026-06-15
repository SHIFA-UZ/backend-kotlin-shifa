ALTER TABLE doctor_settings
    ADD COLUMN IF NOT EXISTS default_start_tab VARCHAR(32) NOT NULL DEFAULT 'home';
