CREATE TABLE treatment_plan_catalog_items (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    code VARCHAR(64),
    title VARCHAR(255) NOT NULL,
    default_price_minor BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'UZS',
    vat_percent NUMERIC(5, 2),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_treatment_catalog_clinic ON treatment_plan_catalog_items(clinic_id);

CREATE TABLE treatment_plans (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    patient_id BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    attending_doctor_id BIGINT REFERENCES doctor_profiles(id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    notes TEXT,
    payment_reminder_days INT,
    created_by_user_id BIGINT REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_treatment_plans_clinic ON treatment_plans(clinic_id);
CREATE INDEX idx_treatment_plans_patient ON treatment_plans(patient_id);

CREATE TABLE treatment_plan_lines (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES treatment_plans(id) ON DELETE CASCADE,
    catalog_item_id BIGINT REFERENCES treatment_plan_catalog_items(id) ON DELETE SET NULL,
    title VARCHAR(255) NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    unit_price_minor BIGINT NOT NULL,
    discount_minor BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'UZS',
    sort_order INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_treatment_plan_lines_plan ON treatment_plan_lines(plan_id);

CREATE TABLE treatment_plan_payments (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES treatment_plans(id) ON DELETE CASCADE,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'UZS',
    method VARCHAR(32) NOT NULL,
    memo TEXT,
    recorded_by_user_id BIGINT REFERENCES users(id),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_treatment_plan_payments_plan ON treatment_plan_payments(plan_id);

ALTER TABLE notifications
    ADD COLUMN treatment_plan_id BIGINT REFERENCES treatment_plans(id);
