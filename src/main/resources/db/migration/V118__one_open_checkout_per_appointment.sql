-- Checkout creation was not idempotent: a retry opened a second PENDING payment for the same appointment.
-- Production holds 5 such appointments (13 surplus rows) as of 2026-10-27.
-- Step 1: keep the newest open row per appointment, cancel the rest (idempotent).
-- Step 2: enforce one open row per appointment. Both run in one transaction. A checkout inserted in the
-- millisecond window between them would make the index build fail; Flyway then aborts the migration and
-- Railway keeps the previous deployment running, so this is safe to retry. The table has tens of rows.
UPDATE payments p
SET status = 'CANCELLED',
    failure_reason = 'duplicate checkout cleanup 2026-10',
    updated_at = now()
WHERE p.status IN ('PENDING', 'AUTHORIZED')
  AND p.appointment_id IS NOT NULL
  AND p.id <> (
      SELECT q.id FROM payments q
      WHERE q.appointment_id = p.appointment_id
        AND q.status IN ('PENDING', 'AUTHORIZED')
      ORDER BY q.created_at DESC, q.id DESC
      LIMIT 1
  );

CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_open_checkout_per_appointment
    ON payments (appointment_id)
    WHERE status IN ('PENDING', 'AUTHORIZED');
