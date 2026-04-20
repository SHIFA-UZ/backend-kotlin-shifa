-- V57: Multi-location support for doctors.
--
-- Introduces `doctor_locations`: a doctor can have one or more practice locations
-- (e.g. "Main Clinic" from 8AM–12PM and "Downtown Office" from 2PM–6PM on the same day).
--
-- Every weekly/date-specific schedule rule and every appointment is now (optionally) tied
-- to a location, so patients can pick a location before choosing a time, and so that
-- overlap validation can prevent the same doctor from being booked at two locations at once.
--
-- Backfill strategy for existing data:
--   - For every doctor that has any schedule rule, appointment, or location fields set on
--     the profile, create a single "primary" location row copying the current profile fields
--     (clinic, address, lat/lon, location_* columns).
--   - Update all existing weekly_schedule_rules / date_specific_schedule_rules /
--     appointments of that doctor to reference the newly created location.
--
-- location_id is left NULLABLE so legacy / video-only appointments can have no location.

-- 1) Create doctor_locations ---------------------------------------------------------------

CREATE TABLE IF NOT EXISTS doctor_locations (
    id                      BIGSERIAL PRIMARY KEY,
    doctor_id               BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    label                   VARCHAR(120) NOT NULL,
    clinic                  TEXT,
    address                 TEXT,
    latitude                DOUBLE PRECISION,
    longitude               DOUBLE PRECISION,
    location_country        VARCHAR(100),
    location_region         VARCHAR(100),
    location_district       VARCHAR(100),
    location_city           VARCHAR(100),
    location_postal_code    VARCHAR(20),
    location_street_address VARCHAR(255),
    is_primary              BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_doctor_locations_doctor
    ON doctor_locations(doctor_id);

-- At most one primary location per doctor.
CREATE UNIQUE INDEX IF NOT EXISTS ux_doctor_locations_primary
    ON doctor_locations(doctor_id)
    WHERE is_primary = TRUE;

-- 2) Add location_id to schedule rules and appointments -----------------------------------

ALTER TABLE weekly_schedule_rules
    ADD COLUMN IF NOT EXISTS location_id BIGINT REFERENCES doctor_locations(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_weekly_rules_location
    ON weekly_schedule_rules(location_id);

ALTER TABLE date_specific_schedule_rules
    ADD COLUMN IF NOT EXISTS location_id BIGINT REFERENCES doctor_locations(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_date_specific_rules_location
    ON date_specific_schedule_rules(location_id);

ALTER TABLE appointments
    ADD COLUMN IF NOT EXISTS location_id BIGINT REFERENCES doctor_locations(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_appointments_location
    ON appointments(location_id);

-- 3) Backfill: one primary location per existing doctor -----------------------------------

-- Any doctor that has profile-level location data OR an existing schedule rule / appointment
-- gets a "Main Clinic" row created from their current profile fields.
WITH doctors_needing_location AS (
    SELECT DISTINCT dp.id AS doctor_id
    FROM doctor_profiles dp
    WHERE
        dp.clinic IS NOT NULL
        OR dp.address IS NOT NULL
        OR dp.location_country IS NOT NULL
        OR dp.location_region IS NOT NULL
        OR dp.location_city IS NOT NULL
        OR dp.location_street_address IS NOT NULL
        OR dp.latitude IS NOT NULL
        OR EXISTS (SELECT 1 FROM weekly_schedule_rules w WHERE w.doctor_id = dp.id)
        OR EXISTS (SELECT 1 FROM date_specific_schedule_rules d WHERE d.doctor_id = dp.id)
        OR EXISTS (SELECT 1 FROM appointments a WHERE a.doctor_id = dp.id)
)
INSERT INTO doctor_locations (
    doctor_id, label, clinic, address,
    latitude, longitude,
    location_country, location_region, location_district,
    location_city, location_postal_code, location_street_address,
    is_primary
)
SELECT
    dp.id,
    COALESCE(NULLIF(TRIM(dp.clinic), ''), 'Main Clinic') AS label,
    dp.clinic,
    dp.address,
    dp.latitude, dp.longitude,
    dp.location_country, dp.location_region, dp.location_district,
    dp.location_city, dp.location_postal_code, dp.location_street_address,
    TRUE
FROM doctor_profiles dp
JOIN doctors_needing_location d ON d.doctor_id = dp.id
WHERE NOT EXISTS (
    SELECT 1 FROM doctor_locations dl WHERE dl.doctor_id = dp.id
);

-- Link every existing schedule rule / appointment to the doctor's primary location.
UPDATE weekly_schedule_rules w
SET location_id = dl.id
FROM doctor_locations dl
WHERE w.location_id IS NULL
  AND dl.doctor_id = w.doctor_id
  AND dl.is_primary = TRUE;

UPDATE date_specific_schedule_rules r
SET location_id = dl.id
FROM doctor_locations dl
WHERE r.location_id IS NULL
  AND dl.doctor_id = r.doctor_id
  AND dl.is_primary = TRUE;

-- Only backfill in-person appointments; keep video-consultation appointments without a
-- location reference (they use the `location` text column = "Video Consultation").
UPDATE appointments a
SET location_id = dl.id
FROM doctor_locations dl
WHERE a.location_id IS NULL
  AND dl.doctor_id = a.doctor_id
  AND dl.is_primary = TRUE
  AND LOWER(COALESCE(a.location, '')) NOT LIKE '%video%';
