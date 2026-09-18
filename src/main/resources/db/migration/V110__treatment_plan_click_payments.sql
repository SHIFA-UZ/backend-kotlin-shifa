-- Link gateway payments to treatment plans / installment items for patient Click checkout.
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS treatment_plan_id BIGINT REFERENCES treatment_plans(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS installment_item_id BIGINT REFERENCES installment_items(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_payments_treatment_plan_id
    ON payments(treatment_plan_id);
CREATE INDEX IF NOT EXISTS idx_payments_installment_item_id
    ON payments(installment_item_id);

-- Idempotent apply: one clinic ledger row per successful online payment.
ALTER TABLE treatment_plan_payments
    ADD COLUMN IF NOT EXISTS online_payment_id BIGINT REFERENCES payments(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_treatment_plan_payments_online_payment
    ON treatment_plan_payments(online_payment_id)
    WHERE online_payment_id IS NOT NULL;
