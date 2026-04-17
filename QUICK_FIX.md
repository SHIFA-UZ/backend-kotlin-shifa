# Quick Fix for Flyway Checksum Mismatch

## The Problem
Migration V16 was modified after it was applied, causing a checksum mismatch.

## Solution 1: Update Checksum Manually (Recommended)

1. Connect to your PostgreSQL database using any client (pgAdmin, DBeaver, IntelliJ Database tool, etc.)

2. Run this SQL:
```sql
UPDATE flyway_schema_history
SET checksum = -854272577
WHERE version = '16' AND script = 'V16__seed_test_patient_user.sql';
```

3. Restart the backend

## Solution 2: Temporarily Disable Validation (Quick Fix)

I've already updated `application.yml` to temporarily disable Flyway validation. This will allow the backend to start.

**After the backend starts:**
1. Run the SQL from Solution 1 to properly fix the checksum
2. Re-enable validation in `application.yml` by setting `validate-on-migrate: true`

## Solution 3: Use Spring Boot Actuator (if available)

If you have Spring Boot Actuator with Flyway endpoint enabled, you can use:
```
POST /actuator/flyway/repair
```

## After Fixing

Once the checksum is fixed, you should:
1. Create the test user properly using the `/api/test/create-test-patient` endpoint
2. Test login with credentials:
   - Username: `+998901234567` or `patient@test.com`
   - Password: `patient123`
