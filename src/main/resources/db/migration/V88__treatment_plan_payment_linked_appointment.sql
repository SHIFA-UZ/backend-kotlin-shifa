-- Attribute plan payments to a specific visit (appointment) for per-row Finance → By appointment status.
ALTER TABLE treatment_plan_payments
    ADD COLUMN linked_appointment_id BIGINT REFERENCES appointments(id) ON DELETE SET NULL;

CREATE INDEX idx_tpp_linked_appt ON treatment_plan_payments(linked_appointment_id);
