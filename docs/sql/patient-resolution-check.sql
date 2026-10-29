-- Read-only, counts only, no PII. Run on production before the release that carries CurrentPatientResolver:
--   railway connect Postgres --environment production --ssh < docs/sql/patient-resolution-check.sql
--
-- For every patient user it computes the profile each old resolver copy found and the one the new resolver finds,
-- mirroring PatientProfileRepository exactly (exact match, highest id wins). Kotlin safe-call chains stop at a null
-- phone, so the old copies never looked at the e-mail when the phone was null; the SQL reproduces that.
--   old_a: user_id, then phone, then e-mail                      (PatientController, PatientMeFormController, PatientCopilotController)
--   old_b: (phone, then e-mail if phone not null), then user_id  (PatientTreatmentPlanController)
--   old_c: phone, then e-mail if phone not null; never user_id   (tasks, chat, appointments, notifications)
--   old_v: trimmed phone, then trimmed lower-cased e-mail         (VideoController)
--   new:   newest profile by user_id, then trimmed phone, then trimmed e-mail (CurrentPatientResolver)
-- changed_vs_*: old and new both found a profile and they differ.   lost_vs_*: old found one, new finds none.
-- gained_vs_*: old found none, new finds one (a fix, not a risk).  multiple_linked: informational.
-- Release rule: every changed_vs_* and lost_vs_* must be 0, or the affected users' duplicate profiles are merged first.
WITH u AS (
  SELECT id, phone, email,
         regexp_replace(phone, '^\s+|\s+$', '', 'g') AS phone_t,
         regexp_replace(email, '^\s+|\s+$', '', 'g') AS email_t
  FROM users
  WHERE role = 'PATIENT' OR id IN (SELECT user_id FROM user_roles WHERE role = 'PATIENT')
),
r AS (
  SELECT u.id AS user_id, u.phone,
    (SELECT p.id FROM patient_profiles p WHERE p.user_id = u.id ORDER BY p.id DESC LIMIT 1)                                     AS by_user,
    (SELECT count(*) FROM patient_profiles p WHERE p.user_id = u.id)                                                           AS n_linked,
    (SELECT p.id FROM patient_profiles p WHERE u.phone IS NOT NULL AND p.phone = u.phone ORDER BY p.id DESC LIMIT 1)           AS by_phone,
    (SELECT p.id FROM patient_profiles p WHERE u.email IS NOT NULL AND p.email = u.email ORDER BY p.id DESC LIMIT 1)           AS by_email,
    (SELECT p.id FROM patient_profiles p WHERE u.phone_t <> '' AND p.phone = u.phone_t ORDER BY p.id DESC LIMIT 1)             AS by_phone_t,
    (SELECT p.id FROM patient_profiles p WHERE u.email_t <> '' AND p.email = u.email_t ORDER BY p.id DESC LIMIT 1)             AS by_email_t,
    (SELECT p.id FROM patient_profiles p WHERE u.email_t <> '' AND p.email = lower(u.email_t) ORDER BY p.id DESC LIMIT 1)      AS by_email_tl
  FROM u
),
x AS (
  SELECT user_id, n_linked,
    COALESCE(by_user, by_phone, by_email)                                              AS old_a,
    COALESCE(CASE WHEN phone IS NULL THEN NULL ELSE COALESCE(by_phone, by_email) END, by_user) AS old_b,
    CASE WHEN phone IS NULL THEN NULL ELSE COALESCE(by_phone, by_email) END           AS old_c,
    COALESCE(by_phone_t, by_email_tl)                                                  AS old_v,
    COALESCE(by_user, by_phone_t, by_email_t)                                          AS new_r
  FROM r
)
SELECT
  count(*)                                                                          AS patient_users,
  count(*) FILTER (WHERE old_a IS NOT NULL AND new_r IS NOT NULL AND old_a <> new_r) AS changed_vs_a,
  count(*) FILTER (WHERE old_b IS NOT NULL AND new_r IS NOT NULL AND old_b <> new_r) AS changed_vs_b,
  count(*) FILTER (WHERE old_c IS NOT NULL AND new_r IS NOT NULL AND old_c <> new_r) AS changed_vs_c,
  count(*) FILTER (WHERE old_v IS NOT NULL AND new_r IS NOT NULL AND old_v <> new_r) AS changed_vs_v,
  count(*) FILTER (WHERE new_r IS NULL AND (old_a IS NOT NULL OR old_b IS NOT NULL OR old_c IS NOT NULL OR old_v IS NOT NULL)) AS lost_vs_any,
  count(*) FILTER (WHERE old_c IS NULL AND new_r IS NOT NULL)                        AS gained_vs_c,
  count(*) FILTER (WHERE new_r IS NULL)                                              AS no_profile,
  count(*) FILTER (WHERE n_linked > 1)                                               AS multiple_linked
FROM x;
