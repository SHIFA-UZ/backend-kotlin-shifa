ALTER TABLE appointments
    ADD COLUMN booked_by_user_id BIGINT REFERENCES users(id);
