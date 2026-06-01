CREATE TABLE schedule_blocks (
    id          BIGSERIAL PRIMARY KEY,
    doctor_id   BIGINT       NOT NULL REFERENCES doctor_profiles (id) ON DELETE CASCADE,
    start_at    TIMESTAMPTZ  NOT NULL,
    end_at      TIMESTAMPTZ  NOT NULL,
    reason      VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT schedule_blocks_end_after_start CHECK (end_at > start_at)
);

CREATE INDEX idx_schedule_blocks_doctor_start ON schedule_blocks (doctor_id, start_at);
