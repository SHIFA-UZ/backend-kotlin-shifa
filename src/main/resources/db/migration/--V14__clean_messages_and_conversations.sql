-- V14__clean_messages_and_conversations.sql
-- Clean up existing messages and conversations to start fresh with corrected logic

-- Delete all messages first (due to foreign key constraint)
DELETE FROM messages;

-- Delete all conversations
DELETE FROM conversations;

-- Reset sequences if needed (optional, but helps keep IDs clean)
-- ALTER SEQUENCE conversations_id_seq RESTART WITH 1;
-- ALTER SEQUENCE messages_id_seq RESTART WITH 1;
