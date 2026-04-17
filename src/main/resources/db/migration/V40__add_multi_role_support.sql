-- =========================
-- V40__add_multi_role_support.sql
-- Multi-role support: Allow one user to have multiple roles (DOCTOR, PATIENT, ADMIN)
-- =========================

-- 1) Create user_roles table (many-to-many: user ↔ roles)
CREATE TABLE IF NOT EXISTS user_roles (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role       TEXT NOT NULL CHECK (role IN ('DOCTOR', 'PATIENT', 'ADMIN')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id, role)
);

CREATE INDEX IF NOT EXISTS idx_user_roles_user_id ON user_roles(user_id);
CREATE INDEX IF NOT EXISTS idx_user_roles_role ON user_roles(role);

-- 2) Backfill: For every existing user, insert their current role into user_roles
INSERT INTO user_roles (user_id, role)
SELECT id, role FROM users
ON CONFLICT (user_id, role) DO NOTHING;

-- 3) Remove UNIQUE constraint from patient_profiles.user_id (if exists)
--    This allows the same user_id to exist in both doctor_profiles and patient_profiles
--    We need to find and drop any unique constraint on patient_profiles.user_id
DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    -- Find unique constraint on patient_profiles.user_id
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'patient_profiles'::regclass
      AND contype = 'u'  -- unique constraint
      AND array_length(conkey, 1) = 1  -- single column
      AND conkey[1] = (
          SELECT attnum FROM pg_attribute 
          WHERE attrelid = 'patient_profiles'::regclass 
          AND attname = 'user_id'
      );
    
    -- Drop the constraint if found
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE patient_profiles DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

-- 4) Add a partial unique index to ensure one user can have at most one patient profile
--    (but same user_id can exist in doctor_profiles)
CREATE UNIQUE INDEX IF NOT EXISTS idx_patient_profiles_user_id_unique 
ON patient_profiles(user_id) 
WHERE user_id IS NOT NULL;

-- 5) Add comment for documentation
COMMENT ON TABLE user_roles IS 'Multi-role support: One user can have multiple roles (DOCTOR, PATIENT, ADMIN). users.role is kept as primary/default role for backward compatibility.';
