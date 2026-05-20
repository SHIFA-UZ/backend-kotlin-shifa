-- Many-to-many between treatment plans and attending doctors so that a single
-- plan that spans weeks of treatment (and several procedures) can record every
-- doctor involved, not just one "primary" attending doctor.
--
-- We keep the existing scalar `attending_doctor_id` column on `treatment_plans`
-- so legacy code continues to work; the application layer mirrors the first
-- selected doctor into that column for backwards compatibility.

CREATE TABLE IF NOT EXISTS treatment_plan_doctors (
    treatment_plan_id  BIGINT NOT NULL REFERENCES treatment_plans(id) ON DELETE CASCADE,
    doctor_profile_id  BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    PRIMARY KEY (treatment_plan_id, doctor_profile_id)
);

CREATE INDEX IF NOT EXISTS ix_treatment_plan_doctors_plan
    ON treatment_plan_doctors(treatment_plan_id);

CREATE INDEX IF NOT EXISTS ix_treatment_plan_doctors_doctor
    ON treatment_plan_doctors(doctor_profile_id);

-- Backfill: copy the existing scalar attending_doctor_id into the new join
-- table so existing plans show their attending doctor in the new list. The
-- DO-NOTHING clause makes this idempotent in case the join table was already
-- populated from elsewhere.
INSERT INTO treatment_plan_doctors (treatment_plan_id, doctor_profile_id)
SELECT id, attending_doctor_id
FROM treatment_plans
WHERE attending_doctor_id IS NOT NULL
ON CONFLICT DO NOTHING;
