-- Extend treatment_plans with title, diagnosis, denormalized financial totals, reminder tracking
ALTER TABLE treatment_plans
    ADD COLUMN title VARCHAR(255),
    ADD COLUMN diagnosis TEXT,
    ADD COLUMN estimated_total_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN actual_total_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN paid_amount_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN remaining_amount_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_payment_reminder_sent_at TIMESTAMPTZ;

-- Extend treatment_plan_lines with per-line tracking
ALTER TABLE treatment_plan_lines
    ADD COLUMN assigned_doctor_id BIGINT REFERENCES doctor_profiles(id) ON DELETE SET NULL,
    ADD COLUMN linked_appointment_id BIGINT REFERENCES appointments(id) ON DELETE SET NULL,
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PLANNED',
    ADD COLUMN specialty_metadata TEXT,
    ADD COLUMN notes TEXT;

CREATE INDEX idx_tpl_assigned_doctor ON treatment_plan_lines(assigned_doctor_id);
CREATE INDEX idx_tpl_linked_appointment ON treatment_plan_lines(linked_appointment_id);

-- Link appointments back to treatment plan lines
ALTER TABLE appointments
    ADD COLUMN linked_treatment_plan_line_id BIGINT REFERENCES treatment_plan_lines(id) ON DELETE SET NULL;

CREATE INDEX idx_appt_treatment_plan_line ON appointments(linked_treatment_plan_line_id);
