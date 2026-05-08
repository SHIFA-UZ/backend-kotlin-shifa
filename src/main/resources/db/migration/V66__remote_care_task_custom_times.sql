-- Allow tasks to define an explicit list of slot times instead of relying on
-- (start_time, interval_hours, times_per_day). Stored as a comma-separated
-- list of HH:MM values (e.g. "08:00,10:00,12:00,17:00,22:00"). When non-null
-- and non-empty the backend uses it verbatim and ignores interval_hours.
ALTER TABLE remote_care_tasks
    ADD COLUMN IF NOT EXISTS custom_times TEXT;

COMMENT ON COLUMN remote_care_tasks.custom_times IS
    'Comma-separated HH:MM slot times (patient timezone). When set, overrides interval_hours-based scheduling.';
