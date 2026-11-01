-- Hourly /api traffic for the admin health page, so its 24 h, 7 day and 30 day views survive a deploy.
-- Counts and route templates only: no paths, no ids, no user data. Rows older than 62 days are deleted.
CREATE TABLE IF NOT EXISTS ops_http_hourly (
    hour             TIMESTAMPTZ PRIMARY KEY,
    requests         BIGINT NOT NULL,
    status_2xx       BIGINT NOT NULL,
    status_304       BIGINT NOT NULL,
    status_3xx_other BIGINT NOT NULL,
    status_401       BIGINT NOT NULL,
    status_403       BIGINT NOT NULL,
    status_4xx_other BIGINT NOT NULL,
    status_5xx       BIGINT NOT NULL,
    latency_bins     BIGINT[] NOT NULL,
    max_ms           BIGINT NOT NULL,
    peak_rps         BIGINT NOT NULL,
    peak_at          TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS ops_http_hourly_4xx (
    hour     TIMESTAMPTZ NOT NULL,
    status   INTEGER NOT NULL,
    method   VARCHAR(16) NOT NULL,
    route    TEXT NOT NULL,
    requests BIGINT NOT NULL,
    PRIMARY KEY (hour, status, method, route)
);
