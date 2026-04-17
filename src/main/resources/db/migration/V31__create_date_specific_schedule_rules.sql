-- Create table for date-specific schedule rules
-- These rules allow doctors to define schedules for specific date ranges
-- and expand existing schedules (e.g., add 5PM-11PM to an existing 8AM-5PM schedule)

CREATE TABLE IF NOT EXISTS date_specific_schedule_rules (
    id BIGSERIAL PRIMARY KEY,
    doctor_id BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    slot_minutes INTEGER NOT NULL CHECK (slot_minutes >= 5),
    CONSTRAINT check_date_range CHECK (start_date <= end_date),
    CONSTRAINT check_time_range CHECK (start_time < end_time)
);

-- Create index for efficient lookups by doctor and date
CREATE INDEX IF NOT EXISTS idx_date_specific_rules_doctor_date 
    ON date_specific_schedule_rules(doctor_id, start_date, end_date);

-- Create index for date range overlap queries
CREATE INDEX IF NOT EXISTS idx_date_specific_rules_date_range 
    ON date_specific_schedule_rules(doctor_id, start_date, end_date);
