-- Allow doctors to mark a billable video service as free (no Stripe/checkout; appointment confirmed immediately).

ALTER TABLE doctor_services
    ADD COLUMN IF NOT EXISTS is_free_consultation BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN doctor_services.is_free_consultation IS
    'When true, patient video bookings with this service skip payment (NOT_REQUIRED, CONFIRMED).';
