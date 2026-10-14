-- Spoken visit facts (medications, diagnoses, therapy) extracted from scribe transcripts.
-- Stay DRAFT until the doctor says "document everything".

CREATE TABLE IF NOT EXISTS patient_visit_facts (
    id              BIGSERIAL PRIMARY KEY,
    doctor_id       BIGINT NOT NULL REFERENCES doctor_profiles(id),
    patient_id      BIGINT NOT NULL REFERENCES patient_profiles(id),
    appointment_id  BIGINT REFERENCES appointments(id),
    fact_type       VARCHAR(32) NOT NULL,
    text            TEXT NOT NULL,
    quote           TEXT,
    schedule_hint   VARCHAR(255),
    status          VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    remote_task_id  BIGINT REFERENCES remote_care_tasks(id),
    reflection_kept BOOLEAN NOT NULL DEFAULT TRUE,
    drop_reason     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    committed_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_visit_facts_patient_doctor
    ON patient_visit_facts (patient_id, doctor_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_visit_facts_appointment
    ON patient_visit_facts (appointment_id);

CREATE INDEX IF NOT EXISTS idx_visit_facts_status
    ON patient_visit_facts (doctor_id, status);

-- Anonymized clinic habit counts from accepted facts (never written into a patient chart).
CREATE TABLE IF NOT EXISTS clinic_visit_patterns (
    id              BIGSERIAL PRIMARY KEY,
    doctor_id       BIGINT NOT NULL REFERENCES doctor_profiles(id),
    pattern_key     VARCHAR(128) NOT NULL,
    pattern_label   TEXT NOT NULL,
    fact_type       VARCHAR(32) NOT NULL,
    occurrence_count INT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_clinic_pattern_doctor_key UNIQUE (doctor_id, pattern_key)
);

CREATE INDEX IF NOT EXISTS idx_clinic_patterns_doctor
    ON clinic_visit_patterns (doctor_id, occurrence_count DESC);
