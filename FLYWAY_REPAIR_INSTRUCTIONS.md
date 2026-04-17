# Flyway Checksum Repair Instructions

## Issue
Migration V14 was already applied to the database, but then the file was modified, causing a checksum mismatch.

## Solution

You have two options:

### Option 1: Update the Checksum (Recommended)
Run this SQL in your PostgreSQL database:

```sql
UPDATE flyway_schema_history 
SET checksum = 1225084826 
WHERE version = '14' AND description = 'admin panel tables';
```

Then restart the backend.

### Option 2: Delete and Re-run (If tables don't exist yet)
If the V14 migration tables haven't been created yet, you can delete the entry and let it re-run:

```sql
DELETE FROM flyway_schema_history WHERE version = '14';
```

**Warning**: Only do this if the tables from V14 don't exist yet, otherwise you'll get errors about existing tables.

## Verify
After running the SQL, verify with:

```sql
SELECT version, description, checksum, installed_on 
FROM flyway_schema_history 
WHERE version = '14';
```

The checksum should be `1225084826`.
