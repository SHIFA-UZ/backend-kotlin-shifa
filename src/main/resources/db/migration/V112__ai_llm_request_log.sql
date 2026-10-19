-- One row per LLM call made by Shifa Intelligence (routing / usage accounting).
-- Stores metadata only: no prompts, no model output, no patient content.

CREATE TABLE IF NOT EXISTS ai_llm_request_log (
    id              BIGSERIAL PRIMARY KEY,
    request_id      VARCHAR(64) NOT NULL,
    doctor_id       BIGINT,
    provider        VARCHAR(32) NOT NULL,
    model           VARCHAR(128) NOT NULL,
    language        VARCHAR(8),
    category        VARCHAR(40),
    purpose         VARCHAR(32) NOT NULL,
    input_tokens    INT,
    output_tokens   INT,
    duration_ms     BIGINT NOT NULL DEFAULT 0,
    outcome         VARCHAR(40) NOT NULL,
    fallback_index  INT NOT NULL DEFAULT 0,
    tool_names      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ai_llm_request_log_provider_time
    ON ai_llm_request_log (provider, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_llm_request_log_doctor_time
    ON ai_llm_request_log (doctor_id, created_at DESC);
