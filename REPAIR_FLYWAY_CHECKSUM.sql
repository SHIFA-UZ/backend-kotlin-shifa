-- =========================
-- REPAIR_FLYWAY_CHECKSUM.sql
-- Run this script manually to repair the Flyway checksum for V14
-- =========================

-- This updates the checksum for V14 to match the current file
-- Run this in your PostgreSQL database before starting the backend

UPDATE flyway_schema_history 
SET checksum = 1225084826 
WHERE version = '14' AND description = 'admin panel tables';

-- Verify the update
SELECT version, description, checksum, installed_on 
FROM flyway_schema_history 
WHERE version = '14';
