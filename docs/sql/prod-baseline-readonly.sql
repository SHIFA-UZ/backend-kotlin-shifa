-- Read-only baseline queries for the production database (roadmap Phase 0 / scorecard).
-- Every statement is a SELECT or SHOW. Run with:
--   railway connect Postgres --environment production --ssh < docs/sql/prod-baseline-readonly.sql
\echo === Q-A duplicate open checkouts
SELECT appointment_id, count(*) AS open_payments, array_agg(id ORDER BY created_at) AS payment_ids
FROM payments WHERE status IN ('PENDING','AUTHORIZED') AND appointment_id IS NOT NULL
GROUP BY appointment_id HAVING count(*) > 1;
\echo === Q-B legacy /photos/ URLs
SELECT 'patient_profiles.photo_url' AS src, count(*) FROM patient_profiles WHERE photo_url LIKE '%/photos/%'
UNION ALL SELECT 'doctor_profiles.avatar_url', count(*) FROM doctor_profiles WHERE avatar_url LIKE '%/photos/%'
UNION ALL SELECT 'doctor_profiles.certificates', count(*) FROM doctor_profiles WHERE certificates LIKE '%/photos/%'
UNION ALL SELECT 'patient_documents.file_path', count(*) FROM patient_documents WHERE file_path LIKE '%/photos/%'
UNION ALL SELECT 'messages.attachment_url', count(*) FROM messages WHERE attachment_url LIKE '%/photos/%';
\echo === Q-C /photos/ folders
SELECT DISTINCT substring(u FROM '/photos/([^/]+)/') AS folder FROM (
  SELECT photo_url AS u FROM patient_profiles WHERE photo_url LIKE '%/photos/%'
  UNION ALL SELECT avatar_url FROM doctor_profiles WHERE avatar_url LIKE '%/photos/%'
  UNION ALL SELECT file_path FROM patient_documents WHERE file_path LIKE '%/photos/%'
  UNION ALL SELECT attachment_url FROM messages WHERE attachment_url LIKE '%/photos/%') t;
\echo === Q-D file extensions in use
SELECT 'patient_documents' AS src, lower(substring(file_path FROM '\.([A-Za-z0-9]+)$')) AS ext, count(*) FROM patient_documents WHERE file_path IS NOT NULL GROUP BY 2
UNION ALL SELECT 'messages', lower(substring(attachment_url FROM '\.([A-Za-z0-9]+)$')), count(*) FROM messages WHERE attachment_url IS NOT NULL GROUP BY 2
UNION ALL SELECT 'patient_photos', lower(substring(photo_url FROM '\.([A-Za-z0-9]+)$')), count(*) FROM patient_profiles WHERE photo_url IS NOT NULL GROUP BY 2
UNION ALL SELECT 'doctor_avatars', lower(substring(avatar_url FROM '\.([A-Za-z0-9]+)$')), count(*) FROM doctor_profiles WHERE avatar_url IS NOT NULL GROUP BY 2
ORDER BY 1, 3 DESC;
\echo === Q-E test account
SELECT id, enabled, created_at, (email IS NOT NULL) AS has_email FROM users WHERE email = 'patient@test.com' OR phone = '+998901234567';
\echo === Q-F largest tables
SELECT relname, n_live_tup FROM pg_stat_user_tables ORDER BY n_live_tup DESC LIMIT 15;
\echo === Q-G max_connections and current
SHOW max_connections;
SELECT count(*) AS current_connections FROM pg_stat_activity;
\echo === Q-H flyway latest
SELECT max(installed_rank) AS applied_migrations, (SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1) AS latest_version FROM flyway_schema_history;
\echo === Q-I extensions
SELECT extname, extversion FROM pg_extension ORDER BY 1;
\echo === Q-J db size
SELECT pg_size_pretty(pg_database_size(current_database())) AS db_size;
