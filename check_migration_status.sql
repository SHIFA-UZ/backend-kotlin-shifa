-- Check if we need to repair Flyway history
-- This script should be run manually if migration V12 was partially applied

-- First, check if tables exist
SELECT 
    CASE WHEN EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'conversations') 
         THEN 'conversations table EXISTS' 
         ELSE 'conversations table MISSING' 
    END as conversations_status,
    CASE WHEN EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'messages') 
         THEN 'messages table EXISTS' 
         ELSE 'messages table MISSING' 
    END as messages_status;

-- If V12 is in flyway_schema_history but tables are missing, delete the entry:
-- DELETE FROM flyway_schema_history WHERE version = '12';
