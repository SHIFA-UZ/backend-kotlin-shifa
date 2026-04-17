-- Enforce unique patient phone: add normalized column and unique constraint.
-- See docs/PATIENT_PHONE_UNIQUENESS.md for design and duplicate handling.

-- 1) Add column
ALTER TABLE patient_profiles ADD COLUMN IF NOT EXISTS phone_normalized TEXT;

-- 2) Backfill: trim, digits only, leading +; empty -> NULL
UPDATE patient_profiles
SET phone_normalized = CASE
    WHEN regexp_replace(trim(COALESCE(phone, '')), '[^0-9]', '', 'g') = '' THEN NULL
    ELSE '+' || regexp_replace(trim(COALESCE(phone, '')), '[^0-9]', '', 'g')
END;

-- 3) Resolve duplicates: keep one profile per phone_normalized (smallest id), delete the rest.
-- Reassign all references (appointments, chat, documents, etc.) to the kept profile, then delete duplicate.
DO $$
DECLARE
  r RECORD;
  dup_id BIGINT;
BEGIN
  FOR r IN
    SELECT phone_normalized, MIN(id) AS kept_id
    FROM patient_profiles
    WHERE phone_normalized IS NOT NULL AND phone_normalized != ''
    GROUP BY phone_normalized
    HAVING COUNT(*) > 1
  LOOP
    FOR dup_id IN
      SELECT id FROM patient_profiles
      WHERE phone_normalized = r.phone_normalized AND id != r.kept_id
    LOOP
      UPDATE appointments SET patient_id = r.kept_id WHERE patient_id = dup_id;
      -- chat_threads has UNIQUE(doctor_id, patient_id); keep one thread per doctor, drop duplicate's
      DELETE FROM chat_threads WHERE patient_id = dup_id;
      UPDATE patient_documents SET patient_id = r.kept_id WHERE patient_id = dup_id;
      UPDATE remote_care_tasks SET patient_id = r.kept_id WHERE patient_id = dup_id;
      UPDATE notifications SET patient_id = r.kept_id WHERE patient_id = dup_id;
      UPDATE ai_draft_notes SET patient_id = r.kept_id WHERE patient_id = dup_id;
      UPDATE patient_documents SET uploaded_by_patient_profile_id = r.kept_id WHERE uploaded_by_patient_profile_id = dup_id;
      DELETE FROM patient_profiles WHERE id = dup_id;
    END LOOP;
  END LOOP;
END $$;

-- 4) Unique partial index (only on non-null, non-empty normalized phone)
CREATE UNIQUE INDEX IF NOT EXISTS idx_patient_profiles_phone_normalized_unique
ON patient_profiles(phone_normalized)
WHERE phone_normalized IS NOT NULL AND phone_normalized != '';
