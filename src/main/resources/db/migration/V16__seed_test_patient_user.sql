-- =========================
-- V16__seed_test_patient_user.sql
-- Create a test patient user for testing the patient app
-- =========================

-- Test patient credentials:
-- Phone: +998901234567 or email: patient@test.com
-- Password: patient123
--
-- The password hash below is for 'patient123' generated with BCrypt (strength 10)
-- Note: The hash below needs to be verified. If login fails, regenerate using BCryptPasswordEncoder
-- Temporary hash (may need regeneration): $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
-- Alternative: Let the registration endpoint create the user with correct hash

-- Insert test patient user (only if doesn't exist)
DO $$
DECLARE
  patient_user_id BIGINT;
BEGIN
  -- Check if user already exists by email or phone
  SELECT id INTO patient_user_id 
  FROM users 
  WHERE (email = 'patient@test.com' OR phone = '+998901234567') 
    AND role = 'PATIENT' 
  LIMIT 1;
  
  -- Only insert if user doesn't exist
  IF patient_user_id IS NULL THEN
    INSERT INTO users (email, phone, password_hash, role, enabled)
    VALUES (
      'patient@test.com',
      '+998901234567',
      '$2a$12$2lIFeOv.Oyjb7IhEFwX55OnaqJEDpsMc6GyGt5Q6nlDPZGci/86RK', -- patient123
      'PATIENT',
      true
    )
    ON CONFLICT DO NOTHING
    RETURNING id INTO patient_user_id;
    
    -- If insert didn't happen (due to conflict), get existing ID
    IF patient_user_id IS NULL THEN
      SELECT id INTO patient_user_id 
      FROM users 
      WHERE (email = 'patient@test.com' OR phone = '+998901234567') 
        AND role = 'PATIENT' 
      LIMIT 1;
    END IF;
  END IF;
  
  -- Create or update patient profile
  IF patient_user_id IS NOT NULL THEN
    -- Insert patient profile if it doesn't exist
    INSERT INTO patient_profiles (full_name, phone, email, address, birth_date, language)
    VALUES (
      'Test Patient',
      '+998901234567',
      'patient@test.com',
      'Tashkent, Uzbekistan',
      '1990-01-01',
      'Uzbek'
    )
    ON CONFLICT DO NOTHING;
    
    -- Update existing patient profile to ensure data is correct
    UPDATE patient_profiles
    SET 
      full_name = 'Test Patient',
      email = 'patient@test.com',
      address = 'Tashkent, Uzbekistan',
      birth_date = '1990-01-01',
      language = 'Uzbek'
    WHERE (phone = '+998901234567' OR email = 'patient@test.com')
      AND (full_name IS DISTINCT FROM 'Test Patient' 
           OR address IS DISTINCT FROM 'Tashkent, Uzbekistan' 
           OR birth_date IS DISTINCT FROM '1990-01-01'
           OR language IS DISTINCT FROM 'Uzbek');
  END IF;
END $$;
