-- V58: Payment foundation (consultations + doctor subscriptions).
-- This migration introduces provider-agnostic payment ledger tables so Shifa can
-- integrate Stripe + local UZ gateways behind one backend model.

ALTER TABLE doctor_profiles
    ADD COLUMN IF NOT EXISTS consultation_price_minor BIGINT,
    ADD COLUMN IF NOT EXISTS consultation_currency VARCHAR(3);

ALTER TABLE doctor_billing
    ADD COLUMN IF NOT EXISTS stripe_connect_account_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS click_merchant_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS payme_merchant_id VARCHAR(255);

ALTER TABLE appointments
    ADD COLUMN IF NOT EXISTS payment_amount_minor BIGINT,
    ADD COLUMN IF NOT EXISTS payment_currency VARCHAR(3),
    ADD COLUMN IF NOT EXISTS payment_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED';

CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,
    external_ref VARCHAR(64) NOT NULL UNIQUE,
    gateway VARCHAR(32) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    appointment_id BIGINT REFERENCES appointments(id) ON DELETE SET NULL,
    payer_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    payee_doctor_id BIGINT REFERENCES doctor_profiles(id) ON DELETE SET NULL,
    subscription_id BIGINT,
    gateway_payment_id VARCHAR(255),
    gateway_checkout_url TEXT,
    paid_at TIMESTAMPTZ,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_payments_appointment_id
    ON payments(appointment_id);
CREATE INDEX IF NOT EXISTS idx_payments_payer_user_created
    ON payments(payer_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payments_gateway_status
    ON payments(gateway, status);

CREATE TABLE IF NOT EXISTS payment_events (
    id BIGSERIAL PRIMARY KEY,
    gateway VARCHAR(32) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payment_id BIGINT REFERENCES payments(id) ON DELETE SET NULL,
    payload TEXT NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    processed_at TIMESTAMPTZ,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_events_gateway_event
    ON payment_events(gateway, event_id);

CREATE TABLE IF NOT EXISTS refunds (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    amount_minor BIGINT NOT NULL,
    reason TEXT,
    status VARCHAR(32) NOT NULL,
    gateway_refund_id VARCHAR(255),
    initiated_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_refunds_payment_id
    ON refunds(payment_id);

CREATE TABLE IF NOT EXISTS subscription_plans (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    price_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    interval VARCHAR(16) NOT NULL,
    features_json TEXT,
    stripe_price_id VARCHAR(255),
    click_plan_id VARCHAR(255),
    payme_plan_id VARCHAR(255),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS doctor_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    doctor_id BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    plan_id BIGINT NOT NULL REFERENCES subscription_plans(id),
    gateway VARCHAR(32) NOT NULL,
    external_subscription_id VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    trial_ends_at TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_doctor_subscriptions_doctor
    ON doctor_subscriptions(doctor_id);
CREATE INDEX IF NOT EXISTS idx_doctor_subscriptions_status
    ON doctor_subscriptions(status);

ALTER TABLE payments
    ADD CONSTRAINT fk_payments_subscription
    FOREIGN KEY (subscription_id) REFERENCES doctor_subscriptions(id) ON DELETE SET NULL;
