-- Remote care tasks: schedule is in patient's timezone so the patient tracks by their local time.
-- Patient app can set this (e.g. from device); doctor sees it when creating a task.

ALTER TABLE patient_profiles
ADD COLUMN time_zone VARCHAR(64);

COMMENT ON COLUMN patient_profiles.time_zone IS 'IANA timezone id (e.g. Europe/Berlin). Used for remote task schedule and reminders; null defaults to UTC in code.';
