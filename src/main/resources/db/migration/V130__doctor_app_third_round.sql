-- BE-20: a booking may follow up an earlier visit of the same patient (POST /api/schedule/book followUpOf).
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS follow_up_of_appointment_id BIGINT
    REFERENCES appointments (id) ON DELETE SET NULL;

-- BE-23: when the clinic last sent a manual installment reminder (POST .../installment-items/{id}/notify).
ALTER TABLE installment_items ADD COLUMN IF NOT EXISTS last_notified_at TIMESTAMPTZ;

-- BE-21: a doctor removes a document they uploaded (soft delete: every read skips rows with deleted_at set).
ALTER TABLE patient_documents ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
