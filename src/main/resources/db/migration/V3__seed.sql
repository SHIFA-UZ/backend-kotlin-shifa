-- Simple seed data for local testing

-- A doctor user + profile
INSERT INTO users (email, phone, password_hash, role)
VALUES ('doctor@clinic.com', '+998900000001', '$2a$10$examplehash', 'DOCTOR')
ON CONFLICT DO NOTHING;

INSERT INTO doctor_profiles (user_id, first_name, last_name, clinic, profession, address)
SELECT id, 'Ulugbek', 'Karimov', 'Tashkent Med Center', 'Cardiologist', 'Yashnabod, Tashkent'
FROM users WHERE email='doctor@clinic.com'
ON CONFLICT DO NOTHING;

-- Two patients
INSERT INTO patient_profiles (full_name, phone, email)
VALUES ('Jasur Karimov', '+998901234567', 'jasur.k@example.com'),
       ('Gulnora Yusupova', '+998957654321', 'gulnora.y@example.com');

-- Weekly rule: Thursday 10:00–13:00, 30-minute slots
INSERT INTO weekly_schedule_rules (doctor_id, weekday, start_time, end_time, slot_minutes)
SELECT dp.id, 4, '10:00', '13:00', 30 FROM doctor_profiles dp
LIMIT 1;
