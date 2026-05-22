-- Per-user daily AI request counters (demo cost protection; timezone applied in app).
CREATE TABLE ai_usage_counter (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL,
    usage_date DATE NOT NULL,
    request_count INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_usage_user_date UNIQUE (user_id, usage_date)
);

CREATE INDEX idx_ai_usage_counter_user_date ON ai_usage_counter(user_id, usage_date);
