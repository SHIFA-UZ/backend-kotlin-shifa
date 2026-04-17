-- =========================
-- V1__init.sql
-- Core tables for Shifa Doctor backend
-- =========================

-- 1) Users (authentication + authorization)
CREATE TABLE IF NOT EXISTS users (
  id             BIGSERIAL PRIMARY KEY,
  email          TEXT UNIQUE,
  phone          TEXT UNIQUE,
  password_hash  TEXT NOT NULL,
  role           TEXT NOT NULL,                 -- DOCTOR | PATIENT | ADMIN
  enabled        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 2) Invitation keys for VerifyKeyScreen (doctor-only onboarding)
CREATE TABLE IF NOT EXISTS invitation_keys (
  id                 BIGSERIAL PRIMARY KEY,
  key_code           TEXT UNIQUE NOT NULL,      -- e.g., "BEKZOD"
  consumed           BOOLEAN NOT NULL DEFAULT FALSE,
  consumed_by_user_id BIGINT REFERENCES users(id),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  consumed_at        TIMESTAMPTZ
);

-- 3) Doctor profile (maps to CreateAccount + AccountInformation + Profile sections)
CREATE TABLE IF NOT EXISTS doctor_profiles (
  id                 BIGSERIAL PRIMARY KEY,
  user_id            BIGINT UNIQUE NOT NULL REFERENCES users(id),
  first_name         TEXT NOT NULL,
  last_name          TEXT NOT NULL,
  dob                DATE,
  gender             TEXT,
  address            TEXT,
  clinic             TEXT,
  profession         TEXT,
  avatar_url         TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 4) Patient profile (used for calendar booking, chat & documents)
CREATE TABLE IF NOT EXISTS patient_profiles (
  id                 BIGSERIAL PRIMARY KEY,
  full_name          TEXT NOT NULL,
  phone              TEXT,
  email              TEXT,
  avatar_url         TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 5) Availability (optional one-off slots, if you ever need them)
CREATE TABLE IF NOT EXISTS availability_slots (
  id             BIGSERIAL PRIMARY KEY,
  doctor_id      BIGINT NOT NULL REFERENCES doctor_profiles(id),
  start_at       TIMESTAMPTZ NOT NULL,
  end_at         TIMESTAMPTZ NOT NULL,
  is_recurring   BOOLEAN NOT NULL DEFAULT FALSE,
  weekday        INT,                             -- 1..7 when recurring
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT slot_time_valid CHECK (end_at > start_at)
);

-- 6) Appointments (maps to your Calendar 'appointment' entries & Home 'Today')
CREATE TABLE IF NOT EXISTS appointments (
  id             BIGSERIAL PRIMARY KEY,
  doctor_id      BIGINT NOT NULL REFERENCES doctor_profiles(id),
  patient_id     BIGINT NOT NULL REFERENCES patient_profiles(id),
  start_at       TIMESTAMPTZ NOT NULL,
  end_at         TIMESTAMPTZ NOT NULL,
  location       TEXT NOT NULL,                   -- "Video Consultation" or clinic address
  reason         TEXT,
  status         TEXT NOT NULL DEFAULT 'REQUESTED', -- REQUESTED | CONFIRMED | CANCELLED | COMPLETED
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT appt_time_valid CHECK (end_at > start_at)
);

-- Helpful indexes for time-range queries
CREATE INDEX IF NOT EXISTS idx_appointments_doctor_time
  ON appointments (doctor_id, start_at, end_at);

-- 7) Chat (doctor-patient thread + messages)
CREATE TABLE IF NOT EXISTS chat_threads (
  id             BIGSERIAL PRIMARY KEY,
  doctor_id      BIGINT NOT NULL REFERENCES doctor_profiles(id),
  patient_id     BIGINT NOT NULL REFERENCES patient_profiles(id),
  UNIQUE (doctor_id, patient_id)
);

CREATE TABLE IF NOT EXISTS messages (
  id               BIGSERIAL PRIMARY KEY,
  thread_id        BIGINT NOT NULL REFERENCES chat_threads(id) ON DELETE CASCADE,
  sender_user_id   BIGINT NOT NULL REFERENCES users(id),
  text             TEXT,
  sent_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  read_at          TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_messages_thread_time
  ON messages (thread_id, sent_at);

-- 8) Documents (used by in-person/video screens & Patients panel)
CREATE TABLE IF NOT EXISTS documents (
  id             BIGSERIAL PRIMARY KEY,
  owner_type     TEXT NOT NULL,                   -- 'PATIENT' | 'DOCTOR'
  owner_id       BIGINT NOT NULL,                 -- references patient_profiles.id or doctor_profiles.id
  title          TEXT,
  file_key       TEXT NOT NULL,                   -- S3/MinIO key
  file_name      TEXT NOT NULL,
  mime_type      TEXT NOT NULL,
  size_bytes     BIGINT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Optional indexes for frequent lookups
CREATE INDEX IF NOT EXISTS idx_documents_owner
  ON documents (owner_type, owner_id, created_at DESC);
