CREATE TABLE financial_records (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    patient_id BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    treatment_plan_id BIGINT REFERENCES treatment_plans(id) ON DELETE SET NULL,
    record_type VARCHAR(32) NOT NULL,
    record_number VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    subtotal_minor BIGINT NOT NULL DEFAULT 0,
    discount_minor BIGINT NOT NULL DEFAULT 0,
    tax_minor BIGINT NOT NULL DEFAULT 0,
    total_minor BIGINT NOT NULL DEFAULT 0,
    paid_minor BIGINT NOT NULL DEFAULT 0,
    remaining_minor BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'UZS',
    issued_at TIMESTAMPTZ,
    due_date DATE,
    notes TEXT,
    created_by_user_id BIGINT REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fin_records_clinic ON financial_records(clinic_id);
CREATE INDEX idx_fin_records_patient ON financial_records(patient_id);
CREATE INDEX idx_fin_records_plan ON financial_records(treatment_plan_id);
CREATE INDEX idx_fin_records_status ON financial_records(status);

-- Link payments to financial records
ALTER TABLE treatment_plan_payments
    ADD COLUMN financial_record_id BIGINT REFERENCES financial_records(id) ON DELETE SET NULL;
