-- The session that registered a doctor's device token: pushes to the device stop when that session expires or is
-- revoked. Rows from before this column keep ringing while the doctor has any live session.
ALTER TABLE doctor_push_tokens ADD COLUMN IF NOT EXISTS session_jti TEXT;
