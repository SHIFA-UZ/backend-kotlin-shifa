# Railway Deployment - Quick Start

## 🚀 10-Minute Setup

### Step 1: Push Code to GitHub
```bash
cd shifa-doctor-backend
git add .
git commit -m "Prepare for Railway"
git push origin main
```

### Step 2: Sign Up to Railway
1. Go to: https://railway.app/
2. Click **"Start a New Project"**
3. Sign up with GitHub

### Step 3: Create Project
1. Click **"New Project"**
2. Select **"Deploy from GitHub repo"**
3. Choose `shifa-doctor-backend`

### Step 4: Add Database
1. Click **"+ New"** → **"Database"** → **"Add PostgreSQL"**
2. Wait for database to be created

### Step 4.5: Add Volume for File Storage
1. Service → **"Settings"** → **"Volumes"**
2. Click **"+ New Volume"**
3. Name: `file-storage`, Mount: `/app/storage`, Size: 1GB
4. Click **"Add"**

### Step 5: Set Environment Variables

In Railway → Your Service → **"Variables"** tab, add:

```
SPRING_PROFILES_ACTIVE=prod
DATABASE_URL=${{Postgres.DATABASE_URL}}
DB_USERNAME=${{Postgres.PGUSER}}
DB_PASSWORD=${{Postgres.PGPASSWORD}}
JWT_SECRET=<generate with: openssl rand -base64 64>
JWT_ISSUER=shifa
PUBLIC_BASE_URL=https://your-app-name.railway.app
STORAGE_ROOT=/app/storage/images
OPENAI_API_KEY=your_openai_key
OPENAI_PROJECT_ID=your_project_id
```

**Generate JWT Secret:**
```bash
# Mac/Linux
openssl rand -base64 64

# Windows PowerShell
[Convert]::ToBase64String((1..64 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
```

### Step 6: Deploy
- Railway auto-deploys on push
- Or click **"Deploy"** button
- Wait ~3-5 minutes

### Step 7: Get Your URL
1. Service → **"Settings"** → **"Networking"**
2. Copy your public domain: `https://your-app.railway.app`
3. **This is your backend API URL!**

### Step 8: Update Frontend
```powershell
cd shifa_doc_app_v1
.\scripts\build_staging.bat https://your-app.railway.app/api
firebase deploy --only hosting
```

## ✅ Done!

Your backend is live at: `https://your-app.railway.app`

## 📝 Full Guide

For detailed instructions, see: [RAILWAY_DEPLOYMENT.md](RAILWAY_DEPLOYMENT.md)
