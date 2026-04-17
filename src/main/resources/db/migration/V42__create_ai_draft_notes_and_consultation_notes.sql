-- =========================
-- V42__create_ai_draft_notes_and_consultation_notes.sql
-- AI draft notes (workflow-integrated assistant) and consultation notes (confirmed)
-- =========================

-- 1) AI draft notes: store streamed AI response until doctor confirms or discards
CREATE TABLE IF NOT EXISTS ai_draft_notes (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  doctor_id         BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
  patient_id        BIGINT NULL REFERENCES patient_profiles(id) ON DELETE SET NULL,
  consultation_id   BIGINT NULL,
  ai_response_text  TEXT NOT NULL,
  ai_label          VARCHAR(255) NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  status            VARCHAR(32) NOT NULL DEFAULT 'GENERATED' CHECK (status IN ('GENERATED', 'CONFIRMED', 'DISCARDED')),
  model_version     VARCHAR(64) NOT NULL,
  prompt_version    VARCHAR(64) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_draft_notes_doctor_id ON ai_draft_notes(doctor_id);
CREATE INDEX IF NOT EXISTS idx_ai_draft_notes_status ON ai_draft_notes(status);
CREATE INDEX IF NOT EXISTS idx_ai_draft_notes_created_at ON ai_draft_notes(created_at);

-- 2) Consultation notes: official notes (from confirmed AI draft or manual entry)
CREATE TABLE IF NOT EXISTS consultation_notes (
  id                BIGSERIAL PRIMARY KEY,
  doctor_id         BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
  patient_id        BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
  appointment_id    BIGINT NULL REFERENCES appointments(id) ON DELETE SET NULL,
  ai_draft_note_id  UUID NULL REFERENCES ai_draft_notes(id) ON DELETE SET NULL,
  subjective        TEXT NULL,
  assessment        TEXT NULL,
  plan              TEXT NULL,
  body              TEXT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  source            VARCHAR(32) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'AI_DRAFT'))
);

CREATE INDEX IF NOT EXISTS idx_consultation_notes_doctor_id ON consultation_notes(doctor_id);
CREATE INDEX IF NOT EXISTS idx_consultation_notes_patient_id ON consultation_notes(patient_id);
CREATE INDEX IF NOT EXISTS idx_consultation_notes_appointment_id ON consultation_notes(appointment_id);
