-- V69: Service groups (per doctor), optional group on services, optional location on prices.

CREATE TABLE IF NOT EXISTS doctor_service_groups (
    id           BIGSERIAL PRIMARY KEY,
    doctor_id    BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    name         VARCHAR(120) NOT NULL,
    sort_order   INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_doctor_service_groups_doctor
    ON doctor_service_groups(doctor_id);

ALTER TABLE doctor_services
    ADD COLUMN IF NOT EXISTS group_id BIGINT REFERENCES doctor_service_groups(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_doctor_services_group
    ON doctor_services(group_id);

-- Optional practice location for a price row: NULL = default / all locations.
ALTER TABLE doctor_service_prices
    ADD COLUMN IF NOT EXISTS location_id BIGINT REFERENCES doctor_locations(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_doctor_service_prices_location
    ON doctor_service_prices(location_id);

DROP INDEX IF EXISTS ux_doctor_service_price_currency;

CREATE UNIQUE INDEX IF NOT EXISTS ux_doctor_service_price_global_currency
    ON doctor_service_prices(service_id, currency)
    WHERE location_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_doctor_service_price_location_currency
    ON doctor_service_prices(service_id, currency, location_id)
    WHERE location_id IS NOT NULL;
