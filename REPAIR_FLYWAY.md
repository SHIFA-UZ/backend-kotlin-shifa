# Fix Flyway Checksum Mismatch for V16

## Current Issue
Migration V16 has been modified, causing a checksum mismatch:
- **Applied to database:** -854272577
- **Resolved locally:** -1325262906

## Solution: Update the Checksum

Run this SQL in your PostgreSQL database (using pgAdmin, DBeaver, IntelliJ Database tool, etc.):

```sql
UPDATE flyway_schema_history
SET checksum = -1325262906
WHERE version = '16' AND script = 'V16__seed_test_patient_user.sql';
```

## Steps:

1. **Connect to your PostgreSQL database:**
   - Database: `shifa`
   - User: `shifa` (or your configured user)
   - Password: `shifa` (or your configured password)

2. **Run the SQL command above**

3. **Verify it worked:**
   ```sql
   SELECT version, description, script, checksum, installed_on
   FROM flyway_schema_history
   WHERE version = '16';
   ```
   The checksum should now be `-1325262906`

4. **Restart your backend** - it should start successfully now

## Alternative: Use Flyway Repair (if you have Flyway CLI)

If you have Flyway CLI installed:
```bash
flyway repair -url=jdbc:postgresql://localhost:5432/shifa -user=shifa -password=shifa
```

## After Backend Starts

Once the backend is running:

1. **Create the test patient user:**
   ```bash
   POST http://localhost:8080/api/test/create-test-patient
   ```

2. **Test login:**
   - Username: `+998901234567` or `patient@test.com`
   - Password: `patient123`

## Note

If you continue to modify the V16 migration file, you'll need to update the checksum again. Consider:
- Leaving V16 as-is and creating new migrations (V17, V18, etc.) for changes
- Or using the `/api/test/create-test-patient` endpoint instead of SQL migrations for test data
