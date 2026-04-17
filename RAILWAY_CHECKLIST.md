# Railway Deployment Checklist

Use this checklist to ensure everything is ready before deploying.

## Pre-Deployment Checklist

### Code Preparation
- [ ] Code is pushed to GitHub repository
- [ ] `railway.json` exists in root directory
- [ ] `Procfile` exists in root directory
- [ ] `application-prod.yml` exists and is configured
- [ ] `build.gradle.kts` is valid and builds locally

### Local Testing
- [ ] App builds successfully: `./gradlew clean build`
- [ ] JAR file is created: `build/libs/*.jar`
- [ ] App runs locally with production profile: `java -jar build/libs/*.jar --spring.profiles.active=prod`

### Railway Account
- [ ] Railway account created at https://railway.app
- [ ] GitHub account connected to Railway
- [ ] Railway has access to your repository

## Deployment Steps

### Step 1: Create Railway Project
- [ ] Created new Railway project
- [ ] Connected GitHub repository
- [ ] Railway detected Java/Gradle project

### Step 2: Add PostgreSQL Database
- [ ] Added PostgreSQL database service
- [ ] Database is running and healthy
- [ ] Noted database connection details

### Step 3: Add Volume for File Storage
- [ ] Added Volume to service
- [ ] Mount path set to `/app/storage`
- [ ] Volume size configured (1GB+)
- [ ] Volume is attached and mounted

### Step 4: Environment Variables
- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] `DATABASE_URL=${{Postgres.DATABASE_URL}}`
- [ ] `DB_USERNAME=${{Postgres.PGUSER}}`
- [ ] `DB_PASSWORD=${{Postgres.PGPASSWORD}}`
- [ ] `JWT_SECRET` (64+ characters, generated securely)
- [ ] `JWT_ISSUER=shifa`
- [ ] `PUBLIC_BASE_URL` (set to your Railway app URL)
- [ ] `STORAGE_ROOT=/app/storage/images` (Railway volume path)
- [ ] `OPENAI_API_KEY` (your OpenAI key)
- [ ] `OPENAI_PROJECT_ID` (your OpenAI project ID)

### Step 5: Deploy
- [ ] Triggered deployment (auto or manual)
- [ ] Build completed successfully
- [ ] App started without errors
- [ ] Health check passing (if configured)

### Step 6: Verify Deployment
- [ ] Got Railway public URL
- [ ] Updated `PUBLIC_BASE_URL` with Railway URL
- [ ] Tested API endpoint: `https://your-app.railway.app/api/auth/login`
- [ ] Checked logs for errors
- [ ] Database migrations ran successfully
- [ ] Tested file upload (e.g., doctor photo)
- [ ] Verified file is accessible via URL
- [ ] Checked volume has files in `/app/storage/images`

### Step 7: Frontend Integration
- [ ] Updated frontend build script with Railway URL
- [ ] Built frontend: `.\scripts\build_staging.bat https://your-app.railway.app/api`
- [ ] Deployed frontend to Firebase
- [ ] Tested full stack (frontend → backend → database)

## Post-Deployment

### Monitoring
- [ ] Set up Railway monitoring/alerts
- [ ] Checked Railway metrics (CPU, Memory)
- [ ] Verified logs are accessible

### Security
- [ ] All secrets are in environment variables (not in code)
- [ ] JWT secret is strong (64+ characters)
- [ ] Database password is secure
- [ ] CORS is configured for frontend domain

### Documentation
- [ ] Backend URL documented
- [ ] Environment variables documented
- [ ] Team members have access (if needed)

## Troubleshooting

If deployment fails, check:
- [ ] Build logs in Railway
- [ ] Environment variables are set correctly
- [ ] Database is accessible
- [ ] Port configuration is correct
- [ ] Java version matches (21)

## Quick Commands Reference

```bash
# Generate JWT Secret (Mac/Linux)
openssl rand -base64 64

# Generate JWT Secret (Windows PowerShell)
[Convert]::ToBase64String((1..64 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))

# Test local build
./gradlew clean build
java -jar build/libs/*.jar --spring.profiles.active=prod

# Check Railway logs
# (In Railway dashboard → Service → Logs)
```

## Success Criteria

✅ Deployment is successful when:
1. Railway shows "Deployed" status
2. Health check returns 200 OK
3. API endpoints respond correctly
4. Database connections work
5. Frontend can connect to backend
6. No errors in Railway logs
