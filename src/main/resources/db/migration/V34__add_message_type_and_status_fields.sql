-- V34__add_message_type_and_status_fields.sql
-- Add new fields to messages table for enhanced chat functionality

-- Add message_type column (enum: TEXT, IMAGE, VOICE, DOCUMENT, SYSTEM)
ALTER TABLE messages ADD COLUMN IF NOT EXISTS message_type VARCHAR(20) DEFAULT 'TEXT' NOT NULL;

-- Add thumbnail_url column for image thumbnails
ALTER TABLE messages ADD COLUMN IF NOT EXISTS thumbnail_url VARCHAR(500);

-- Add file_size column for attachment size in bytes
ALTER TABLE messages ADD COLUMN IF NOT EXISTS file_size BIGINT;

-- Add duration column for voice messages (in seconds)
ALTER TABLE messages ADD COLUMN IF NOT EXISTS duration INTEGER;

-- Add status column (enum: SENDING, SENT, DELIVERED, READ)
ALTER TABLE messages ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'SENT' NOT NULL;

-- Update existing messages to have proper defaults
-- Set message_type based on existing attachment_url
UPDATE messages 
SET message_type = CASE 
    WHEN attachment_url IS NOT NULL THEN 
        CASE 
            WHEN attachment_name LIKE '%.pdf' OR attachment_name LIKE '%.doc' OR attachment_name LIKE '%.docx' THEN 'DOCUMENT'
            WHEN attachment_name LIKE '%.mp3' OR attachment_name LIKE '%.wav' OR attachment_name LIKE '%.m4a' OR attachment_name LIKE '%.ogg' THEN 'VOICE'
            WHEN attachment_name LIKE '%.jpg' OR attachment_name LIKE '%.jpeg' OR attachment_name LIKE '%.png' OR attachment_name LIKE '%.gif' THEN 'IMAGE'
            ELSE 'DOCUMENT'
        END
    ELSE 'TEXT'
END
WHERE message_type = 'TEXT';

-- Set status based on is_read
UPDATE messages 
SET status = CASE 
    WHEN is_read = true THEN 'READ'
    ELSE 'SENT'
END
WHERE status = 'SENT';

-- Add comments for documentation
COMMENT ON COLUMN messages.message_type IS 'Type of message: TEXT, IMAGE, VOICE, DOCUMENT, SYSTEM';
COMMENT ON COLUMN messages.thumbnail_url IS 'URL to thumbnail image for image messages';
COMMENT ON COLUMN messages.file_size IS 'Size of attachment file in bytes';
COMMENT ON COLUMN messages.duration IS 'Duration of voice message in seconds';
COMMENT ON COLUMN messages.status IS 'Delivery status: SENDING, SENT, DELIVERED, READ';
