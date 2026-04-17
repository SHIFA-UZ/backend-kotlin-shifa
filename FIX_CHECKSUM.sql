-- Run this SQL in your PostgreSQL database to fix the Flyway checksum mismatch
-- You can use pgAdmin, DBeaver, or any PostgreSQL client

-- Update the checksum for V16 to match the current file content
-- Current checksum: -1325262906
UPDATE flyway_schema_history
SET checksum = -1325262906
WHERE version = '16' AND script = 'V16__seed_test_patient_user.sql';

-- Verify the update
SELECT version, description, script, checksum, installed_on, success
FROM flyway_schema_history
WHERE version = '16'
ORDER BY installed_rank DESC
LIMIT 1;
