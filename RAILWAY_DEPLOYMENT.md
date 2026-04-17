# Railway Deployment Guide

This guide will help you deploy your Shifa Doctor backend to Railway for staging.

## Prerequisites

1. **GitHub Account** - Railway deploys from GitHub
2. **Railway Account** - Sign up at [railway.app](https://railway.app)
3. **Backend code in GitHub** - Your code should be in a GitHub repository

## Step 1: Prepare Your Code

### 1.1 Ensure Your Code is in GitHub

If not already:
```bash
cd shifa-doctor-backend

# Initialize git if needed
git init

# Add all files
git add .

# Commit
git commit -m "Prepare for Railway deployment"

# Create GitHub repo and push
# (Do this on GitHub.com, then:)
git remote add origin https://github.com/yourusername/shifa-doctor-backend.git
git push -u origin main
```

### 1.2 Verify Configuration Files

Make sure these files exist:
- ✅ `railway.json` - Railway build configuration
- ✅ `Procfile` - Process definition
- ✅ `application-prod.yml` - Production config
- ✅ `build.gradle.kts` - Build configuration

## Step 2: Sign Up / Login to Railway

1. Go to: https://railway.app/
2. Click **"Start a New Project"**
3. Sign up with GitHub (recommended) or email
4. Authorize Railway to access your GitHub

## Step 3: Create New Project

1. In Railway dashboard, click **"New Project"**
2. Select **"Deploy from GitHub repo"**
3. Choose your `shifa-doctor-backend` repository
4. Railway will detect it's a Java/Gradle project

## Step 4: Add PostgreSQL Database

1. In your Railway project, click **"+ New"**
2. Select **"Database"** → **"Add PostgreSQL"**
3. Railway will create a PostgreSQL database
4. **Important:** Note the connection details (you'll need them)

## Step 4.5: Add Volume for File Storage

1. In Railway → Your Service → **"Settings"** → **"Volumes"**
2. Click **"+ New Volume"**
3. Configure:
   - **Name**: `file-storage`
   - **Mount Path**: `/app/storage`
   - **Size**: 1GB (can increase later)
4. Click **"Add"**

**Why?** This provides persistent storage for uploaded files (photos, documents, certificates) that survives deployments.

**See detailed guide**: [RAILWAY_VOLUME_SETUP.md](RAILWAY_VOLUME_SETUP.md)

## Step 5: Configure Environment Variables

In your Railway service, go to **"Variables"** tab and add:

### Required Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Spring profile | `prod` |
| `DATABASE_URL` | PostgreSQL connection string | *(Auto-set by Railway)* |
| `DB_USERNAME` | Database username | *(Auto-set by Railway)* |
| `DB_PASSWORD` | Database password | *(Auto-set by Railway)* |
| `JWT_SECRET` | JWT signing secret (64+ chars) | Generate with: `openssl rand -base64 64` |
| `JWT_ISSUER` | JWT issuer | `shifa` |
| `PUBLIC_BASE_URL` | Your Railway app URL (for file serving) | `https://your-app.railway.app` |
| `STORAGE_ROOT` | File storage path (Railway volume) | `/app/storage/images` |
| `OPENAI_API_KEY` | OpenAI API key | `sk-...` |
| `OPENAI_PROJECT_ID` | OpenAI project ID | `proj_...` |

### How to Add Variables

1. Click on your service in Railway
2. Go to **"Variables"** tab
3. Click **"+ New Variable"**
4. Add each variable one by one

### Database Variables (Auto-set by Railway)

Railway automatically sets these when you add PostgreSQL:
- `DATABASE_URL` - Full connection string
- `PGHOST` - Database host
- `PGPORT` - Database port
- `PGDATABASE` - Database name
- `PGUSER` - Database user
- `PGPASSWORD` - Database password

**You need to map them to Spring variables:**

Add these variables in Railway:
```
DB_USERNAME=${{Postgres.PGUSER}}
DB_PASSWORD=${{Postgres.PGPASSWORD}}
DATABASE_URL=${{Postgres.DATABASE_URL}}
```

Or manually set:
```
DATABASE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
DB_USERNAME=${{Postgres.PGUSER}}
DB_PASSWORD=${{Postgres.PGPASSWORD}}
```

### Generate JWT Secret

**Windows (PowerShell):**
```powershell
# Generate secure JWT secret
[Convert]::ToBase64String((1..64 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
```

**Mac/Linux:**
```bash
openssl rand -base64 64
```

Copy the output and use it as `JWT_SECRET` value.

## Step 6: Configure Port

Railway sets a `PORT` environment variable. Update your `application-prod.yml` to use it:

The config already uses `${SERVER_PORT:8080}`, but Railway uses `PORT`. Add this variable:

```
SERVER_PORT=${{PORT}}
```

Or Railway will automatically use PORT if you don't set SERVER_PORT.

## Step 7: Deploy

1. Railway will automatically start building when you:
   - Push code to GitHub, OR
   - Click **"Deploy"** button

2. Watch the build logs:
   - Click on your service
   - Go to **"Deployments"** tab
   - Click on the latest deployment
   - Watch the build process

3. Wait for deployment to complete (~3-5 minutes)

## Step 8: Get Your Backend URL

After successful deployment:

1. Go to your service in Railway
2. Click on the service
3. Go to **"Settings"** tab
4. Under **"Networking"**, you'll see:
   - **Public Domain**: `https://your-app-name.railway.app`
   - Or generate a custom domain

**This is your backend API URL!** Use it in your frontend:
```
https://your-app-name.railway.app/api
```

## Step 9: Test Your Backend

1. Visit: `https://your-app-name.railway.app/actuator/health` (if actuator is enabled)
2. Or test an API endpoint: `https://your-app-name.railway.app/api/auth/login`
3. Check logs in Railway dashboard for any errors

## Step 10: Update Frontend

Now update your frontend build with the Railway URL:

```powershell
cd shifa_doc_app_v1
.\scripts\build_staging.bat https://your-app-name.railway.app/api
```

## Troubleshooting

### Build Fails

**Error: "Gradle not found"**
- Railway should auto-detect Gradle
- Check that `build.gradle.kts` exists in root

**Error: "Java version mismatch"**
- Railway uses Java 21 by default (matches your config)
- If issues, add `JAVA_VERSION=21` variable

### App Won't Start

**Error: "Port already in use"**
- Railway sets `PORT` automatically
- Make sure your app uses `${PORT}` or `${SERVER_PORT}`

**Error: "Database connection failed"**
- Check `DATABASE_URL` is set correctly
- Verify PostgreSQL service is running
- Check database credentials

**Error: "JWT_SECRET not set"**
- Make sure `JWT_SECRET` environment variable is set
- Must be at least 64 characters

### Database Migration Issues

**Error: "Flyway migration failed"**
- Check database is accessible
- Verify `DATABASE_URL` format is correct
- Check Flyway migration files are in `src/main/resources/db/migration`

### CORS Issues

If frontend can't connect:
- Update `PUBLIC_BASE_URL` to your Railway URL
- Configure CORS in your Spring Boot app to allow your frontend domain

## Environment Variables Checklist

Before deploying, ensure you have:

- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] `DATABASE_URL` (from Railway PostgreSQL)
- [ ] `DB_USERNAME` (from Railway PostgreSQL)
- [ ] `DB_PASSWORD` (from Railway PostgreSQL)
- [ ] `JWT_SECRET` (generated, 64+ chars)
- [ ] `JWT_ISSUER=shifa`
- [ ] `PUBLIC_BASE_URL` (your Railway URL)
- [ ] `OPENAI_API_KEY`
- [ ] `OPENAI_PROJECT_ID`
- [ ] `SERVER_PORT=${{PORT}}` (optional, Railway handles this)

## Railway Free Tier Limits

- **$5 credit/month** (enough for staging)
- **512MB RAM** per service
- **1GB storage**
- **100GB bandwidth/month**

For production with 100 doctors, consider upgrading.

## Updating Your Deployment

After making code changes:

1. **Push to GitHub:**
   ```bash
   git add .
   git commit -m "Your changes"
   git push origin main
   ```

2. **Railway auto-deploys:**
   - Railway watches your GitHub repo
   - Automatically rebuilds and redeploys on push
   - Takes ~3-5 minutes

3. **Or manually deploy:**
   - Go to Railway dashboard
   - Click **"Redeploy"** button

## Monitoring

Railway provides:
- **Logs** - Real-time application logs
- **Metrics** - CPU, Memory, Network usage
- **Deployments** - Deployment history
- **Health checks** - Automatic health monitoring

## Next Steps

After backend is deployed:

1. ✅ Get your Railway backend URL
2. ✅ Update frontend build script with backend URL
3. ✅ Deploy frontend to Firebase
4. ✅ Test the full stack
5. ✅ Share staging URLs with testers

## Support

- Railway Docs: https://docs.railway.app
- Railway Discord: https://discord.gg/railway
- Railway Status: https://status.railway.app
