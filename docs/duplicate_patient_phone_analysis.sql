-- Run this if V45__patient_phone_normalized_unique.sql fails with a unique constraint violation.
-- It lists existing duplicate phone_normalized values so you can resolve them before re-running the migration.

-- Duplicates by normalized phone (after V45 backfill has run; if migration failed at CREATE INDEX, run the backfill manually first):
SELECT
  phone_normalized,
  COUNT(*) AS count,
  array_agg(id ORDER BY id) AS patient_ids,
  array_agg(full_name ORDER BY id) AS names
FROM patient_profiles
WHERE phone_normalized IS NOT NULL AND phone_normalized != ''
GROUP BY phone_normalized
HAVING COUNT(*) > 1;
