-- =========================
-- V15__seed_admin_user.sql
-- Create the first admin user for the system
-- =========================

-- Default admin credentials:
-- Email: admin@shifa.local
-- Password: Admin123! (change this immediately after first login)
-- 
-- IMPORTANT: Change the default password immediately after first login!
--
-- The password hash below is for 'Admin123!' generated with BCrypt (strength 10)
-- To generate a new hash, use: BCryptPasswordEncoder().encode("your-password")

-- Insert admin user
INSERT INTO users (email, password_hash, role, enabled, created_at, updated_at)
VALUES (
  'admin@shifa.local',
  '$2a$12$DXtXJHUgVlSBdYR/uApjCe0eGchx0rEUE3UzBAQbXmw9NBiWITZ6u', -- Admin123!
  'ADMIN',
  true,
  now(),
  now()
)
ON CONFLICT (email) DO NOTHING;

-- Get the admin user ID and create admin profile
DO $$
DECLARE
  admin_user_id BIGINT;
BEGIN
  SELECT id INTO admin_user_id FROM users WHERE email = 'admin@shifa.local' AND role = 'ADMIN';
  
  IF admin_user_id IS NOT NULL THEN
    INSERT INTO admin_profiles (user_id, first_name, last_name, admin_level, created_at, updated_at)
    VALUES (
      admin_user_id,
      'System',
      'Administrator',
      'SUPER_ADMIN',
      now(),
      now()
    )
    ON CONFLICT (user_id) DO NOTHING;
  END IF;
END $$;
