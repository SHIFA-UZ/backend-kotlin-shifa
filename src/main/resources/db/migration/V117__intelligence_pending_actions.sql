CREATE TABLE IF NOT EXISTS intelligence_pending_actions (
    id               BIGSERIAL PRIMARY KEY,
    doctor_id        BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    conversation_id  VARCHAR(128) NOT NULL,
    action_type      VARCHAR(32) NOT NULL,
    token            UUID NOT NULL UNIQUE,
    payload_json     JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMPTZ NOT NULL,
    consumed_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_intel_pending_actions_active
    ON intelligence_pending_actions
       (doctor_id, conversation_id, action_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_intel_pending_actions_expiry
    ON intelligence_pending_actions (expires_at);
