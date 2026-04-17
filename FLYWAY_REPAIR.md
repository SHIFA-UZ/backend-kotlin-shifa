# Fix Flyway Checksum Mismatch for V16

The error occurs because migration `V16__seed_test_patient_user.sql` was modified after it was already applied to the database.

## Option 1: Repair Flyway (Recommended)

Run this SQL command in your PostgreSQL database:

```sql
-- Update the checksum for V16 to match the current file
UPDATE flyway_schema_history
SET checksum = -854272577
WHERE version = '16' AND script = 'V16__seed_test_patient_user.sql';
```

Then restart the backend.

## Option 2: Use Flyway CLI (if installed)

If you have Flyway CLI installed:

```bash
cd shifa-doctor-backend
flyway repair -url=jdbc:postgresql://localhost:5432/shifa -user=your_db_user -password=your_db_password
```

## Option 3: Use Flyway Maven/Gradle Plugin

Add this to your build.gradle.kts and run:

```kotlin
// In build.gradle.kts, add Flyway plugin (if not already present)
// Then run: ./gradlew flywayRepair
```

## Option 4: Manual SQL Script

Run the SQL script in `repair-flyway.sql`:

```bash
psql -U your_db_user -d shifa -f repair-flyway.sql
```

## Quick Fix: Connect to PostgreSQL and run:

```sql
\c shifa
UPDATE flyway_schema_history
SET checksum = -854272577
WHERE version = '16' AND script = 'V16__seed_test_patient_user.sql';
```

After running the repair, restart your backend and it should start successfully.
