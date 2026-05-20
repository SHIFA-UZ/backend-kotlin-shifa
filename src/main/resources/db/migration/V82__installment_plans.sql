CREATE TABLE installment_plans (
    id BIGSERIAL PRIMARY KEY,
    treatment_plan_id BIGINT NOT NULL REFERENCES treatment_plans(id) ON DELETE CASCADE,
    total_amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'UZS',
    num_installments INT NOT NULL,
    frequency VARCHAR(32) NOT NULL DEFAULT 'MONTHLY',
    start_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    notes TEXT,
    created_by_user_id BIGINT REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_installment_plans_tp ON installment_plans(treatment_plan_id);

CREATE TABLE installment_items (
    id BIGSERIAL PRIMARY KEY,
    installment_plan_id BIGINT NOT NULL REFERENCES installment_plans(id) ON DELETE CASCADE,
    sequence_number INT NOT NULL,
    due_date DATE NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'UZS',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    paid_at TIMESTAMPTZ,
    payment_id BIGINT REFERENCES treatment_plan_payments(id) ON DELETE SET NULL,
    notes TEXT
);

CREATE INDEX idx_installment_items_plan ON installment_items(installment_plan_id);
CREATE INDEX idx_installment_items_due ON installment_items(due_date);
CREATE INDEX idx_installment_items_status ON installment_items(status);
