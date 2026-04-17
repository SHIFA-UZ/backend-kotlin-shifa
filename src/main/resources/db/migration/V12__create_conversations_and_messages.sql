-- V12__create_conversations_and_messages.sql
-- Creates tables for messaging functionality

-- Drop incomplete tables if they exist (from failed previous attempts)
DROP TABLE IF EXISTS messages CASCADE;
DROP TABLE IF EXISTS conversations CASCADE;

-- Create conversations table
CREATE TABLE conversations (
    id BIGSERIAL PRIMARY KEY,
    doctor_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    doctor_participant_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    patient_participant_id BIGINT REFERENCES patient_profiles(id) ON DELETE CASCADE,
    last_message_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT check_participant CHECK (
        (doctor_participant_id IS NOT NULL AND patient_participant_id IS NULL) OR
        (doctor_participant_id IS NULL AND patient_participant_id IS NOT NULL)
    )
);

-- Create indexes for conversations
CREATE INDEX idx_conversations_doctor_user ON conversations (doctor_user_id);
CREATE INDEX idx_conversations_doctor_participant ON conversations (doctor_participant_id);
CREATE INDEX idx_conversations_patient_participant ON conversations (patient_participant_id);
CREATE INDEX idx_conversations_last_message_at ON conversations (last_message_at DESC);

-- Create messages table
CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipient_doctor_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    recipient_patient_id BIGINT REFERENCES patient_profiles(id) ON DELETE CASCADE,
    text TEXT,
    attachment_url TEXT,
    attachment_name TEXT,
    is_read BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT check_recipient CHECK (
        (recipient_doctor_id IS NOT NULL AND recipient_patient_id IS NULL) OR
        (recipient_doctor_id IS NULL AND recipient_patient_id IS NOT NULL)
    )
);

-- Create indexes for messages
CREATE INDEX idx_messages_conversation ON messages (conversation_id);
CREATE INDEX idx_messages_sender ON messages (sender_user_id);
CREATE INDEX idx_messages_recipient_doctor ON messages (recipient_doctor_id);
CREATE INDEX idx_messages_recipient_patient ON messages (recipient_patient_id);
CREATE INDEX idx_messages_created_at ON messages (created_at DESC);
CREATE INDEX idx_messages_is_read ON messages (is_read) WHERE is_read = false;
