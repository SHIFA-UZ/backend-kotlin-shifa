# Local database setup

If you see **"FATAL: password authentication failed for user shifa"**, the app’s credentials don’t match your local PostgreSQL.

## Option 1: Use your existing PostgreSQL user (recommended)

Set environment variables to the username and password you already use:

**PowerShell:**
```powershell
$env:DB_USERNAME = "postgres"      # or your PostgreSQL username
$env:DB_PASSWORD = "your_password" # your actual password
.\gradlew bootRun
```

**Cmd:**
```cmd
set DB_USERNAME=postgres
set DB_PASSWORD=your_password
gradlew.bat bootRun
```

The app will connect to `localhost:5432/shifa`. Ensure the database exists:

```sql
CREATE DATABASE shifa;
```

(If you use the `postgres` user, you can create the DB while connected as that user.)

## Option 2: Create a `shifa` user that matches the defaults

If you prefer not to use env vars, create a user and database that match `application.yml`:

```sql
CREATE USER shifa WITH PASSWORD 'shifa';
CREATE DATABASE shifa OWNER shifa;
```

Then run `.\gradlew bootRun` with no env vars.

## Check which user you use

From a terminal:

```powershell
psql -U postgres -d postgres -c "\du"
```

Use the username you normally connect with for `DB_USERNAME`, and that user’s password for `DB_PASSWORD`.
