CREATE TABLE IF NOT EXISTS intelligence_booking_workflows (
    id                  UUID PRIMARY KEY,
    doctor_id           BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    conversation_id     VARCHAR(128) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    patient_id          BIGINT REFERENCES patient_profiles(id) ON DELETE SET NULL,
    patient_name        VARCHAR(255),
    appointment_date    DATE,
    appointment_time    TIME,
    proposal_json       JSONB,
    confirmation_token  UUID,
    idempotency_key     UUID NOT NULL UNIQUE,
    appointment_id      BIGINT REFERENCES appointments(id) ON DELETE SET NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_booking_workflow_doctor_conversation
    ON intelligence_booking_workflows (doctor_id, conversation_id, updated_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uq_booking_workflow_active_conversation
    ON intelligence_booking_workflows (doctor_id, conversation_id)
    WHERE status IN (
        'COLLECTING_DETAILS',
        'CHECKING_AVAILABILITY',
        'AWAITING_CONFIRMATION',
        'EXECUTING'
    );
