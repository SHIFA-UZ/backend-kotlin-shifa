-- =========================
-- V2__schedule_and_profile_extensions.sql
-- Weekly rules + billing + settings
-- =========================

-- 1) Add schedule validity (end date) to doctor profiles
ALTER TABLE doctor_profiles
  ADD COLUMN IF NOT EXISTS schedule_valid_until DATE;

-- 2) Weekly schedule rules (recurring slots generator)
CREATE TABLE IF NOT EXISTS weekly_schedule_rules (
  id            BIGSERIAL PRIMARY KEY,
  doctor_id     BIGINT NOT NULL REFERENCES doctor_profiles(id),
  weekday       INT NOT NULL,               -- 1..7 (Mon..Sun)
  start_time    TIME NOT NULL,              -- local wall-clock time
  end_time      TIME NOT NULL,              -- local wall-clock time
  slot_minutes  INT NOT NULL,               -- e.g., 30
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT wsr_time_valid CHECK (end_time > start_time)
);

CREATE INDEX IF NOT EXISTS idx_weekly_rules_doctor_weekday
  ON weekly_schedule_rules (doctor_id, weekday);

-- 3) Doctor billing (Payment & Invoicing section in your Profile screen)
CREATE TABLE IF NOT EXISTS doctor_billing (
  id             BIGSERIAL PRIMARY KEY,
  doctor_id      BIGINT UNIQUE NOT NULL REFERENCES doctor_profiles(id),
  billing_name   TEXT,
  billing_email  TEXT,
  iban           TEXT,
  tax_id         TEXT,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 4) Doctor settings (Country, Language, 2FA, Encrypted docs) — Profile screen
CREATE TABLE IF NOT EXISTS doctor_settings (
  id              BIGSERIAL PRIMARY KEY,
  doctor_id       BIGINT UNIQUE NOT NULL REFERENCES doctor_profiles(id),
  country         TEXT,
  language        TEXT,
  two_factor      BOOLEAN NOT NULL DEFAULT FALSE,
  encrypted_docs  BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
