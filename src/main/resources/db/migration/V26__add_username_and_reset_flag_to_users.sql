-- V26__add_username_and_reset_flag_to_users.sql
ALTER TABLE users ADD COLUMN username VARCHAR(255) UNIQUE;
ALTER TABLE users ADD COLUMN force_password_reset BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE patient_profiles ADD COLUMN user_id BIGINT UNIQUE REFERENCES users(id);
