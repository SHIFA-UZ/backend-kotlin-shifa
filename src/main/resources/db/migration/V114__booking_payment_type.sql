-- Pay-now vs pay-at-clinic on a service. Free visits stay on is_free_consultation.
-- Existing rows default to PAY_NOW so current video checkout keeps working.

ALTER TABLE doctor_services
    ADD COLUMN IF NOT EXISTS payment_type VARCHAR(16) NOT NULL DEFAULT 'PAY_NOW';

ALTER TABLE appointments
    ADD COLUMN IF NOT EXISTS is_video_consultation BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS payment_type VARCHAR(16),
    ADD COLUMN IF NOT EXISTS hold_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS refund_required BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE appointments
SET is_video_consultation = TRUE
WHERE is_video_consultation = FALSE
  AND location ILIKE '%video%';

CREATE INDEX IF NOT EXISTS idx_appointments_payment_hold
    ON appointments (hold_expires_at)
    WHERE status = 'PENDING_PAYMENT';
