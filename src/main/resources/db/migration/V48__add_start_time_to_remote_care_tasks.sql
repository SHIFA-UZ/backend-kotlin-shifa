-- Add start_time and interval_hours to remote_care_tasks.
-- Window is start_time to 20:00: 1–3 times spread evenly; 4+ use interval_hours (every N hours).
ALTER TABLE remote_care_tasks ADD COLUMN IF NOT EXISTS start_time TIME;
ALTER TABLE remote_care_tasks ADD COLUMN IF NOT EXISTS interval_hours INTEGER;
