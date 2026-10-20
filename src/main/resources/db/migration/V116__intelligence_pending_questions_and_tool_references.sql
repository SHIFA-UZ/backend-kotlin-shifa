ALTER TABLE intelligence_booking_workflows
    ADD COLUMN IF NOT EXISTS pending_question_type VARCHAR(40),
    ADD COLUMN IF NOT EXISTS pending_question_json JSONB,
    ADD COLUMN IF NOT EXISTS pending_question_token UUID;

CREATE TABLE IF NOT EXISTS intelligence_tool_references (
    id               BIGSERIAL PRIMARY KEY,
    doctor_id        BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    conversation_id  VARCHAR(128) NOT NULL,
    result_set_id    UUID NOT NULL,
    tool_name        VARCHAR(64) NOT NULL,
    reference_type   VARCHAR(32) NOT NULL,
    entity_id        BIGINT NOT NULL,
    patient_id       BIGINT REFERENCES patient_profiles(id) ON DELETE SET NULL,
    local_date       DATE,
    local_time       TIME,
    status           VARCHAR(32),
    payload_json     JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_intel_tool_refs_lookup
    ON intelligence_tool_references
       (doctor_id, conversation_id, reference_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_intel_tool_refs_expiry
    ON intelligence_tool_references (expires_at);
