-- Repair Flyway checksum for V16 migration
-- Run this SQL script to update the checksum in flyway_schema_history

-- Update the checksum for V16 to match the current file
-- The new checksum is: -854272577 (calculated by Flyway for the current file content)

UPDATE flyway_schema_history
SET checksum = -854272577
WHERE version = '16' AND script = 'V16__seed_test_patient_user.sql';

-- Verify the update
SELECT version, description, script, checksum, installed_on
FROM flyway_schema_history
WHERE version = '16'
ORDER BY installed_rank DESC;
