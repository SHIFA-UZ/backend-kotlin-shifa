-- Store FCM token for doctor app push notifications (similar to patient_profiles.fcm_token).

ALTER TABLE doctor_profiles
ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(255);

