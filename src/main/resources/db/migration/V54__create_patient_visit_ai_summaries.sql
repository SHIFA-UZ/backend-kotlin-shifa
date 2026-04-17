-- Patient-facing AI visit summaries generated from finalized doctor notes.
CREATE TABLE IF NOT EXISTS patient_visit_ai_summaries (
  id              BIGSERIAL PRIMARY KEY,
  appointment_id  BIGINT NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
  patient_id      BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
  language        VARCHAR(8) NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','READY','FAILED')),
  content_json    TEXT NULL,
  source_hash     VARCHAR(128) NULL,
  model_version   VARCHAR(64) NOT NULL,
  generated_at    TIMESTAMPTZ NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (appointment_id, language)
);

CREATE INDEX IF NOT EXISTS idx_patient_visit_ai_summaries_patient ON patient_visit_ai_summaries(patient_id);
CREATE INDEX IF NOT EXISTS idx_patient_visit_ai_summaries_status ON patient_visit_ai_summaries(status);
