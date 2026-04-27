-- V60: Structured doctor services with multi-currency pricing.

CREATE TABLE IF NOT EXISTS doctor_services (
    id BIGSERIAL PRIMARY KEY,
    doctor_id BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    title VARCHAR(160) NOT NULL,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_doctor_services_doctor
    ON doctor_services(doctor_id);

CREATE TABLE IF NOT EXISTS doctor_service_prices (
    id BIGSERIAL PRIMARY KEY,
    service_id BIGINT NOT NULL REFERENCES doctor_services(id) ON DELETE CASCADE,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_doctor_service_prices_service
    ON doctor_service_prices(service_id);

CREATE UNIQUE INDEX IF NOT EXISTS ux_doctor_service_price_currency
    ON doctor_service_prices(service_id, currency);

ALTER TABLE appointments
    ADD COLUMN IF NOT EXISTS service_id BIGINT REFERENCES doctor_services(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS service_title VARCHAR(160);
