-- V13__repair_conversations_and_messages.sql
-- Repair migration for V12 - handles partial application

-- Drop messages table if it exists but is incomplete (missing conversation_id column)
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'messages') THEN
        IF NOT EXISTS (
            SELECT FROM information_schema.columns 
            WHERE table_schema = 'public' 
            AND table_name = 'messages' 
            AND column_name = 'conversation_id'
        ) THEN
            DROP TABLE messages CASCADE;
        END IF;
    END IF;
END $$;

-- Drop conversations table if it exists but messages depends on it incorrectly
-- (We'll recreate both together)
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'conversations') THEN
        IF NOT EXISTS (
            SELECT FROM information_schema.columns 
            WHERE table_schema = 'public' 
            AND table_name = 'conversations' 
            AND column_name = 'doctor_user_id'
        ) THEN
            DROP TABLE conversations CASCADE;
        END IF;
    END IF;
END $$;

-- Now ensure conversations table exists with all columns
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'conversations') THEN
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
    END IF;
END $$;

-- Create indexes for conversations if they don't exist
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'conversations') THEN
        CREATE INDEX IF NOT EXISTS idx_conversations_doctor_user ON conversations (doctor_user_id);
        CREATE INDEX IF NOT EXISTS idx_conversations_doctor_participant ON conversations (doctor_participant_id);
        CREATE INDEX IF NOT EXISTS idx_conversations_patient_participant ON conversations (patient_participant_id);
        CREATE INDEX IF NOT EXISTS idx_conversations_last_message_at ON conversations (last_message_at DESC);
    END IF;
END $$;

-- Ensure messages table exists with all columns
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'messages') THEN
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
    END IF;
END $$;

-- Create indexes for messages if they don't exist
DO $$
BEGIN
    IF EXISTS (
        SELECT FROM information_schema.columns 
        WHERE table_schema = 'public' 
        AND table_name = 'messages' 
        AND column_name = 'conversation_id'
    ) THEN
        CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages (conversation_id);
        CREATE INDEX IF NOT EXISTS idx_messages_sender ON messages (sender_user_id);
        CREATE INDEX IF NOT EXISTS idx_messages_recipient_doctor ON messages (recipient_doctor_id);
        CREATE INDEX IF NOT EXISTS idx_messages_recipient_patient ON messages (recipient_patient_id);
        CREATE INDEX IF NOT EXISTS idx_messages_created_at ON messages (created_at DESC);
        CREATE INDEX IF NOT EXISTS idx_messages_is_read ON messages (is_read) WHERE is_read = false;
    END IF;
END $$;
