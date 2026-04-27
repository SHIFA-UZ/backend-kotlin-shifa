-- V59: Add grace/suspension timestamps for doctor subscriptions.
ALTER TABLE doctor_subscriptions
    ADD COLUMN IF NOT EXISTS grace_period_ends_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ;
