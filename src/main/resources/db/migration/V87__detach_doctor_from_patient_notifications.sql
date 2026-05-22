-- Detach `doctor_id` from any notification row that is patient-targeted but was
-- erroneously tagged with the attending doctor. The doctor's feed is filtered
-- by `doctor_id`, so leaving the value set leaks patient-only messages (e.g.
-- "Your treatment plan status is now CANCELLED", installment reminders,
-- payment-due reminders) into the doctor's notifications list.
--
-- Only the patient-facing notification types are touched. Doctor-targeted
-- types (DOCUMENT_ACCESS_REQUEST, TASK_*, APPOINTMENT_BOOKED_BY_PATIENT, etc.)
-- are left alone — those legitimately need `doctor_id`.
UPDATE notifications
SET doctor_id = NULL
WHERE doctor_id IS NOT NULL
  AND patient_id IS NOT NULL
  AND type IN (
    'TREATMENT_PLAN_UPDATED',
    'TREATMENT_PLAN_PAYMENT_REMINDER',
    'INSTALLMENT_SCHEDULE_CREATED',
    'INSTALLMENT_DUE_SOON',
    'INSTALLMENT_DUE_TODAY',
    'INSTALLMENT_OVERDUE',
    'APPOINTMENT_REMINDER',
    'APPOINTMENT_CANCELLED',
    'APPOINTMENT_CHANGED',
    'CONSULTATION_PAYMENT_REMINDER',
    'CONSULTATION_PAYMENT_DUE_24H',
    'CONSULTATION_PAYMENT_DUE_6H',
    'CONSULTATION_PAYMENT_DUE_1H',
    'PROPHYLAXIS_REMINDER',
    'SIGNATURE_REQUESTED'
  );
