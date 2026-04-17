-- Shifa Global Time Architecture v2: doctor practice timezone (IANA, e.g. Europe/Berlin, America/New_York).
-- Historical data was interpreted as Asia/Tashkent; backfill so existing appointments remain correct.

ALTER TABLE doctor_profiles
ADD COLUMN time_zone VARCHAR(64);

UPDATE doctor_profiles
SET time_zone = 'Asia/Tashkent'
WHERE time_zone IS NULL;

ALTER TABLE doctor_profiles
ALTER COLUMN time_zone SET NOT NULL;

COMMENT ON COLUMN doctor_profiles.time_zone IS 'IANA timezone id for practice (e.g. Europe/Berlin). Used for scheduling and "today" only; storage remains UTC.';
