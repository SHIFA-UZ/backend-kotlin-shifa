-- Store FCM token for patient app push notifications
ALTER TABLE patient_profiles ADD COLUMN IF NOT EXISTS fcm_token TEXT;
