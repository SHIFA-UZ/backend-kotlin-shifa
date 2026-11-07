-- Idempotency-Key replay store (shared/api/IdempotencyFilter): the first 2xx answer to a money-recording POST,
-- per account and key, for 24 hours. A row without response_status is a request still running.
CREATE TABLE IF NOT EXISTS idempotency_keys (
    account_id      VARCHAR(64)  NOT NULL,
    idem_key        VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    response_status INT,
    response_type   VARCHAR(255),
    response_body   BYTEA,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, idem_key)
);
CREATE INDEX IF NOT EXISTS idx_idempotency_keys_created_at ON idempotency_keys (created_at);
