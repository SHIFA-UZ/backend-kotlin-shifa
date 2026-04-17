-- Unlock the test patient user account
-- Run this SQL to unlock the account if it's locked

-- Unlock user account by setting locked_until to NULL and resetting failed attempts
UPDATE users
SET 
    locked_until = NULL,
    failed_login_attempts = 0
WHERE (email = 'patient@test.com' OR phone = '+998901234567')
  AND role = 'PATIENT';

-- Verify the unlock
SELECT 
    id,
    email,
    phone,
    role,
    enabled,
    locked_until,
    failed_login_attempts,
    last_login_at
FROM users
WHERE (email = 'patient@test.com' OR phone = '+998901234567')
  AND role = 'PATIENT';
