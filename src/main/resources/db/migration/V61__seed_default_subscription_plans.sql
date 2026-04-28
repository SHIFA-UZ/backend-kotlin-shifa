-- V61: Seed default doctor subscription plans.
-- Idempotent upsert so existing plan customizations survive repeated deploys.

INSERT INTO subscription_plans (
    code,
    name,
    price_minor,
    currency,
    interval,
    features_json,
    enabled,
    created_at,
    updated_at
)
VALUES
    (
        'BASIC',
        'Basic',
        9900,
        'EUR',
        'MONTH',
        '{"maxPatientsPerMonth":null,"telemedicine":true,"prioritySupport":false}',
        TRUE,
        now(),
        now()
    ),
    (
        'PRO',
        'Pro',
        19900,
        'EUR',
        'MONTH',
        '{"maxPatientsPerMonth":null,"telemedicine":true,"prioritySupport":true}',
        TRUE,
        now(),
        now()
    )
ON CONFLICT (code) DO UPDATE
SET
    name = EXCLUDED.name,
    price_minor = EXCLUDED.price_minor,
    currency = EXCLUDED.currency,
    interval = EXCLUDED.interval,
    features_json = EXCLUDED.features_json,
    enabled = EXCLUDED.enabled,
    updated_at = now();
