-- Doctor FCM tokens (especially web push) can exceed 255 characters.
ALTER TABLE doctor_profiles
    ALTER COLUMN fcm_token TYPE TEXT;
