
-- V2__update_patient_profiles.sql
-- ✅ Add new columns to patient_profiles
ALTER TABLE patient_profiles
ADD COLUMN IF NOT EXISTS address TEXT,
ADD COLUMN IF NOT EXISTS birth_date DATE,
ADD COLUMN IF NOT EXISTS language TEXT;

-- ✅ Insert 10 sample Uzbek patients
INSERT INTO patient_profiles (full_name, phone, email, avatar_url, address, birth_date, language)
VALUES
('Jasur Karimov', '+998 90 123 45 67', 'jasur.k@example.com', NULL, 'Tashkent, Yunusabad', '1990-03-12', 'Uzbek, Russian'),
('Gulnora Yusupova', '+998 95 765 43 21', 'gulnora.y@example.com', NULL, 'Samarkand', '1985-06-01', 'Uzbek'),
('Ulugbek Tursunov', '+998 97 555 00 11', 'ulugbek.t@example.com', NULL, 'Tashkent', '1992-09-21', 'Uzbek, English'),
('Dilshoda Rasulova', '+998 93 111 22 33', 'dilshoda.r@example.com', NULL, 'Bukhara', '1988-07-15', 'Uzbek'),
('Shavkat Nematov', '+998 99 444 55 66', 'shavkat.n@example.com', NULL, 'Namangan', '1983-11-30', 'Uzbek'),
('Nodira Akhmedova', '+998 90 321 00 77', 'nodira.a@example.com', NULL, 'Andijan', '1995-04-18', 'Uzbek'),
('Azamat Rakhimov', '+998 91 700 88 44', 'azamat.r@example.com', NULL, 'Fergana', '1989-02-25', 'Uzbek'),
('Malika Saidova', '+998 93 555 66 77', 'malika.s@example.com', NULL, 'Khiva', '1993-08-09', 'Uzbek'),
('Rustam Bekmurodov', '+998 94 222 33 44', 'rustam.b@example.com', NULL, 'Jizzakh', '1987-12-05', 'Uzbek'),
('Sevara Kholmatova', '+998 95 888 99 00', 'sevara.k@example.com', NULL, 'Termez', '1991-05-22', 'Uzbek, Russian');
