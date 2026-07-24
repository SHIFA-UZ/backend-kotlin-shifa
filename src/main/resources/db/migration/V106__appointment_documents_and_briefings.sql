-- Link patient documents to appointments (optional pre-appointment attachments).
ALTER TABLE patient_documents
    ADD COLUMN IF NOT EXISTS appointment_id BIGINT NULL;

ALTER TABLE patient_documents
    DROP CONSTRAINT IF EXISTS fk_patient_documents_appointment;

ALTER TABLE patient_documents
    ADD CONSTRAINT fk_patient_documents_appointment
        FOREIGN KEY (appointment_id) REFERENCES appointments (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_patient_documents_appointment_id
    ON patient_documents (appointment_id);

-- Auto-generated AI briefing scoped to appointment attachments.
CREATE TABLE IF NOT EXISTS appointment_briefings (
    id              BIGSERIAL PRIMARY KEY,
    appointment_id  BIGINT NOT NULL UNIQUE,
    status          VARCHAR(32) NOT NULL,
    briefing_text   TEXT,
    document_count  INT NOT NULL DEFAULT 0,
    generated_at    TIMESTAMPTZ,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_appointment_briefings_appointment
        FOREIGN KEY (appointment_id) REFERENCES appointments (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_appointment_briefings_status
    ON appointment_briefings (status);
