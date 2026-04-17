-- Multiple validity periods per doctor (e.g. 1 Mar–31 Mar and 8 Apr–30 Apr).
-- Slots are valid on a date if it falls within any period.
CREATE TABLE IF NOT EXISTS schedule_validity_periods (
  id         BIGSERIAL PRIMARY KEY,
  doctor_id  BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
  valid_from DATE NOT NULL,
  valid_until DATE NOT NULL,
  CONSTRAINT schedule_validity_periods_range CHECK (valid_until >= valid_from)
);

CREATE INDEX IF NOT EXISTS idx_schedule_validity_periods_doctor_id
  ON schedule_validity_periods (doctor_id);
