ALTER TABLE doctor_profiles
    ADD COLUMN IF NOT EXISTS admin_trial_period_months SMALLINT NOT NULL DEFAULT 6;

ALTER TABLE doctor_profiles
    ADD COLUMN IF NOT EXISTS admin_monthly_charge_usd SMALLINT NOT NULL DEFAULT 30;

ALTER TABLE doctor_profiles
    ADD CONSTRAINT chk_doctor_profiles_admin_trial_period_months
        CHECK (admin_trial_period_months >= 1 AND admin_trial_period_months <= 12);

ALTER TABLE doctor_profiles
    ADD CONSTRAINT chk_doctor_profiles_admin_monthly_charge_usd
        CHECK (admin_monthly_charge_usd IN (15, 20, 25, 30, 35, 40, 45, 50));
