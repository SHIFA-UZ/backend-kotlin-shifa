-- Durable operational preferences for Shifa Intelligence (doctor-scoped, optionally clinic-scoped).
-- Separate from clinical_rag_chunks: this is never a store of chart content or transcripts.
-- Requires pgvector (created in V72).

CREATE TABLE IF NOT EXISTS intelligence_memories (
    id                BIGSERIAL PRIMARY KEY,
    scope_type        VARCHAR(16) NOT NULL CHECK (scope_type IN ('DOCTOR', 'CLINIC')),
    owner_doctor_id   BIGINT REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    clinic_id         BIGINT REFERENCES clinics(id) ON DELETE CASCADE,
    memory_type       VARCHAR(32) NOT NULL,
    content           TEXT NOT NULL,
    content_key       VARCHAR(96),
    confidence        REAL NOT NULL DEFAULT 0,
    source_request_id VARCHAR(64),
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    superseded_by     BIGINT REFERENCES intelligence_memories(id),
    embedding         vector(1536),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ,
    CONSTRAINT chk_intelligence_memory_scope CHECK (
        (scope_type = 'DOCTOR' AND owner_doctor_id IS NOT NULL)
        OR (scope_type = 'CLINIC' AND clinic_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_intel_mem_doctor_status
    ON intelligence_memories (owner_doctor_id, status, expires_at);

CREATE INDEX IF NOT EXISTS idx_intel_mem_clinic_status
    ON intelligence_memories (clinic_id, status, expires_at);

-- One ACTIVE fact per (doctor, key): a changed preference supersedes the previous row.
CREATE UNIQUE INDEX IF NOT EXISTS uq_intel_mem_doctor_key_active
    ON intelligence_memories (owner_doctor_id, content_key)
    WHERE status = 'ACTIVE' AND scope_type = 'DOCTOR' AND content_key IS NOT NULL;
