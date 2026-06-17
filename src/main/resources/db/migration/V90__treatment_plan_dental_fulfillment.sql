-- FDI teeth-chart snapshot for comprehensive treatment plans (same JSON shape as appointment dental doc v2).
ALTER TABLE treatment_plans
    ADD COLUMN IF NOT EXISTS dental_plan_documentation TEXT;

-- Session linking an appointment to a comprehensive plan (billing mode audit).
CREATE TABLE IF NOT EXISTS treatment_plan_appointment_links (
    id                  BIGSERIAL PRIMARY KEY,
    plan_id             BIGINT NOT NULL REFERENCES treatment_plans(id) ON DELETE CASCADE,
    appointment_id      BIGINT NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    billing_mode        VARCHAR(32) NOT NULL,
    created_by_user_id  BIGINT REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_plan_appt_link UNIQUE (plan_id, appointment_id)
);

CREATE INDEX IF NOT EXISTS idx_tp_appt_links_appt ON treatment_plan_appointment_links(appointment_id);

-- Records which planned line was fulfilled on which visit (idempotent per line).
CREATE TABLE IF NOT EXISTS treatment_plan_line_fulfillments (
    id              BIGSERIAL PRIMARY KEY,
    line_id         BIGINT NOT NULL REFERENCES treatment_plan_lines(id) ON DELETE CASCADE,
    appointment_id  BIGINT NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    completed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_line_fulfillment UNIQUE (line_id)
);

CREATE INDEX IF NOT EXISTS idx_tp_line_fulfill_appt ON treatment_plan_line_fulfillments(appointment_id);
