-- Patient document category + team-visibility flag.
--
-- Visibility rules introduced by this migration:
--   * Documents uploaded by patients are always visible to every doctor that
--     has access to the patient (no explicit grant required).
--   * Documents uploaded by doctors are shared with all doctors of the
--     patient ONLY when tagged with a "medical result" category
--     (MRI, blood test, ultrasound, ...). Doctor-private categories
--     (appointment notes, 025-2 forms, remote-task documents, ...) keep the
--     existing creator-only + access-grant model.
--
-- Schema additions:
--   * patient_documents.category               : optional tag chosen at upload time
--   * patient_documents.is_shared_with_team    : computed at upload time, drives
--                                                the new visibility check.
--
-- Backfill:
--   * Existing patient-uploaded docs become shared-with-team.
--   * Existing doctor-uploaded docs stay private (default FALSE) so already
--     created appointment notes / forms / scans keep their previous visibility.

ALTER TABLE patient_documents
    ADD COLUMN IF NOT EXISTS category TEXT;

ALTER TABLE patient_documents
    ADD COLUMN IF NOT EXISTS is_shared_with_team BOOLEAN NOT NULL DEFAULT FALSE;

-- Patient-uploaded documents: always visible to the whole care team.
UPDATE patient_documents
SET is_shared_with_team = TRUE
WHERE uploaded_by_patient_profile_id IS NOT NULL;

-- Helpful index for listing/queries that filter by visibility per patient.
CREATE INDEX IF NOT EXISTS idx_patient_documents_patient_shared
    ON patient_documents (patient_id, is_shared_with_team);
