CREATE TABLE clinic_finance_audit_log (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    action_type VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id BIGINT,
    details JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cfa_clinic ON clinic_finance_audit_log(clinic_id);
CREATE INDEX idx_cfa_created ON clinic_finance_audit_log(created_at DESC);
