-- Allow CLINIC_STAFF in user_roles (PostgreSQL drops named CHECK when present).
ALTER TABLE user_roles DROP CONSTRAINT IF EXISTS user_roles_role_check;
ALTER TABLE user_roles ADD CONSTRAINT user_roles_role_check CHECK (
    role IN ('DOCTOR', 'PATIENT', 'ADMIN', 'CLINIC_STAFF')
);
