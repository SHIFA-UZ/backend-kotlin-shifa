# Complete Deployment Guide

This guide will help you deploy:
1. **Backend** to Railway
2. **Doctor App** to Firebase

---

## Prerequisites

- [ ] GitHub account with backend repository
- [ ] Railway account (sign up at https://railway.app)
- [ ] Firebase account (sign up at https://console.firebase.google.com)
- [ ] Node.js installed (for Firebase CLI)
- [ ] Flutter installed and configured

---

## Part 1: Deploy Backend to Railway

### Step 1: Prepare Your Repository

Ensure your backend code is pushed to GitHub:

```powershell
cd C:\shifa-doctor-backend
git add .
git commit -m "Prepare for Railway deployment"
git push origin main
```

### Step 2: Create Railway Project

1. Go to https://railway.app
2. Click **"New Project"**
3. Select **"Deploy from GitHub repo"**
4. Choose your `shifa-doctor-backend` repository
5. Railway will auto-detect it's a Java/Gradle project

### Step 3: Add PostgreSQL Database

1. In Railway project, click **"+ New"**
2. Select **"Database"** → **"Add PostgreSQL"**
3. Wait for database to be created
4. Railway will automatically set `DATABASE_URL` environment variable

### Step 4: Add Volume for File Storage (Optional but Recommended)

1. In Railway → Your Service → **"Settings"** → **"Volumes"**
2. Click **"+ New Volume"**
3. Configure:
   - **Name**: `file-storage`
   - **Mount Path**: `/app/storage`
   - **Size**: 1GB
4. Click **"Add"**

**Why?** This provides persistent storage for uploaded files (photos, documents, certificates).

### Step 5: Configure Environment Variables

In Railway → Your Service → **"Variables"** tab, add these variables:

#### Required Variables

| Variable | Value | Description |
|----------|-------|-------------|
| `SPRING_PROFILES_ACTIVE` | `prod` | Spring profile |
| `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` | Auto-set by Railway PostgreSQL |
| `JWT_SECRET` | *(Generate below)* | JWT signing secret (64+ chars) |
| `JWT_ISSUER` | `shifa` | JWT issuer |
| `PUBLIC_BASE_URL` | `https://your-app.railway.app` | Your Railway app URL (set after deployment) |
| `FRONTEND_URL` | `https://your-firebase-app.web.app` | Your Firebase app URL (set after frontend deployment) |
| `STORAGE_ROOT` | `/app/storage/images` | File storage path |
| `OPENAI_API_KEY` | `sk-...` | Your OpenAI API key |
| `OPENAI_PROJECT_ID` | `proj_...` | Your OpenAI project ID |
| `DAILY_API_KEY` | `7e56...` | Daily.co API key for video calls |

#### Generate JWT Secret

**Windows (PowerShell):**
```powershell
[Convert]::ToBase64String((1..64 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
```

**Mac/Linux:**
```bash
openssl rand -base64 64
```

Copy the output and use it as `JWT_SECRET` value.

### Step 6: Deploy

Railway will automatically start building when you:
- Push code to GitHub, OR
- Click **"Deploy"** button

Watch the build logs:
1. Click on your service
2. Go to **"Deployments"** tab
3. Click on the latest deployment
4. Wait for deployment to complete (~3-5 minutes)

### Step 7: Get Your Backend URL

After successful deployment:

1. Go to your service in Railway
2. Click on the service
3. Go to **"Settings"** tab
4. Under **"Networking"**, you'll see:
   - **Public Domain**: `https://your-app-name.railway.app`

**This is your backend API URL!** 

**Important:** Update `PUBLIC_BASE_URL` in Railway variables to this URL.

### Step 8: Test Your Backend

1. Visit: `https://your-app-name.railway.app/actuator/health`
2. Should return: `{"status":"UP"}`
3. Test API: `https://your-app-name.railway.app/api/auth/login`

---

## Part 2: Deploy Doctor App to Firebase

### Step 1: Install Firebase CLI

```powershell
npm install -g firebase-tools
```

**Note:** If you get an error, install Node.js first: https://nodejs.org/

### Step 2: Login to Firebase

```powershell
firebase login
```

This will open a browser - sign in with your Google account.

### Step 3: Create Firebase Project (If Not Created)

1. Go to: https://console.firebase.google.com/
2. Click **"Add project"** (or **"Create a project"**)
3. Enter project name: `shifa-doctor-staging` (or your preferred name)
4. Click **"Continue"**
5. Disable Google Analytics (optional) → Click **"Create project"**
6. Wait for project to be created → Click **"Continue"**

### Step 4: Initialize Firebase Hosting (One-time Setup)

```powershell
cd C:\shifa_doc_app_v1
firebase init hosting
```

**Answer the prompts:**
- **"Which Firebase features do you want to set up?"** → Select **"Hosting"** (use spacebar to select, Enter to confirm)
- **"Please select an option"** → Select **"Use an existing project"**
- **"Select a default Firebase project"** → Select your project (e.g., `shifa-doctor-staging`)
- **"What do you want to use as your public directory?"** → Type: `build/web`
- **"Configure as a single-page app (rewrite all urls to /index.html)?"** → Type: **Y** (Yes)
- **"Set up automatic builds and deploys with GitHub?"** → Type: **N** (No)
- **"File build/web/index.html already exists. Overwrite?"** → Type: **N** (No)

### Step 5: Build the App with Your Railway Backend URL

**Replace `https://your-app.railway.app` with your actual Railway backend URL:**

```powershell
cd C:\shifa_doc_app_v1
.\scripts\build_staging.bat https://your-app.railway.app AIzaSyYourGoogleMapsKey
```

**Example** (if your Railway URL is `https://shifa-backend.railway.app`):
```powershell
.\scripts\build_staging.bat https://shifa-backend.railway.app AIzaSyD8CSmFnNEcUH7JbA-QgRhbHiRBWTt0Jg4
```

**Important:** 
- Do NOT add `/api` to the URL. The code automatically adds `/api` to all API paths.
- Google Maps API key is required for geocoding to work (location picker)
- Get your Google Maps API key from: https://console.cloud.google.com/google/maps-apis
- Wait for build to complete (~2-5 minutes)

### Step 6: Deploy to Firebase

```powershell
firebase deploy --only hosting
```

This will:
- Upload your built app to Firebase
- Deploy to Firebase Hosting
- Give you a public URL

**Wait for deployment** (~30 seconds)

### Step 7: Get Your App URL

After deployment, you'll see:

```
✔ Deploy complete!

Project Console: https://console.firebase.google.com/project/shifa-doctor-staging/overview
Hosting URL: https://shifa-doctor-staging.web.app
```

**Your app is live at the Hosting URL!** 🎉

### Step 8: Update Backend CORS

Update Railway environment variables:

1. Go to Railway → Your Service → **"Variables"**
2. Update `FRONTEND_URL` to your Firebase URL: `https://shifa-doctor-staging.web.app`
3. Railway will automatically redeploy with new CORS settings

---

## Quick Reference Commands

### Backend (Railway)

```powershell
# Push code changes (auto-deploys)
cd C:\shifa-doctor-backend
git add .
git commit -m "Your changes"
git push origin main

# View logs in Railway dashboard
# Go to: Railway → Your Service → "Deployments" → Latest deployment
```

### Frontend (Firebase)

```powershell
# Build with backend URL and Google Maps API key
cd C:\shifa_doc_app_v1
.\scripts\build_staging.bat https://your-app.railway.app AIzaSyYourGoogleMapsKey

# Deploy to Firebase
firebase deploy --only hosting

# Check Firebase projects
firebase projects:list

# Switch Firebase project
firebase use staging
```

---

## Environment Variables Checklist

### Railway (Backend)

Before deploying, ensure you have:

- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] `DATABASE_URL=${{Postgres.DATABASE_URL}}` (auto-set)
- [ ] `JWT_SECRET` (generated, 64+ chars)
- [ ] `JWT_ISSUER=shifa`
- [ ] `PUBLIC_BASE_URL=https://your-app.railway.app` (set after deployment)
- [ ] `FRONTEND_URL=https://your-firebase-app.web.app` (set after frontend deployment)
- [ ] `STORAGE_ROOT=/app/storage/images`
- [ ] `OPENAI_API_KEY` (your OpenAI key)
- [ ] `OPENAI_PROJECT_ID` (your OpenAI project ID)
- [ ] `DAILY_API_KEY` (your Daily.co API key for video calls)

### Firebase (Frontend)

The frontend uses build-time environment variables set via `--dart-define`:

- `API_BASE_URL` - Set via build script (your Railway URL)
- `ENVIRONMENT=staging` - Set automatically by build script

---

## Troubleshooting

### Backend Issues

**Build Fails:**
- Check Railway build logs for errors
- Verify `build.gradle.kts` exists
- Ensure Java 21 is available

**App Won't Start:**
- Check `DATABASE_URL` is set correctly
- Verify `JWT_SECRET` is set (64+ chars)
- Check logs in Railway dashboard

**Database Connection Failed:**
- Verify PostgreSQL service is running
- Check `DATABASE_URL` format
- Railway auto-sets this, but verify in Variables tab

**CORS Errors:**
- Update `FRONTEND_URL` in Railway to your Firebase URL
- Verify `PUBLIC_BASE_URL` matches your Railway URL
- Check backend logs for CORS configuration

### Frontend Issues

**"firebase: command not found":**
- Install Firebase CLI: `npm install -g firebase-tools`
- Ensure Node.js is installed

**Build Failed:**
- Check Flutter is installed: `flutter --version`
- Try: `flutter clean` then rebuild
- Verify you're in correct directory

**CORS Errors in Browser:**
- Verify `FRONTEND_URL` in Railway matches your Firebase URL
- Check backend CORS configuration allows Firebase domain
- Backend should allow: `https://*.web.app` and `https://*.firebaseapp.com`

**API Calls Failing:**
- Verify Railway backend is running
- Check Railway backend URL is correct in build command
- Test backend directly: `https://your-app.railway.app/api/auth/login`
- Check browser console (F12) for errors

**Firebase Project Not Found:**
- Make sure you created the project in Firebase Console
- Check `.firebaserc` file has correct project ID
- Run `firebase use staging` to switch projects

---

## Deployment Workflow

### Initial Deployment

1. ✅ Deploy backend to Railway
2. ✅ Get Railway backend URL
3. ✅ Update `PUBLIC_BASE_URL` in Railway
4. ✅ Build frontend with Railway URL
5. ✅ Deploy frontend to Firebase
6. ✅ Get Firebase frontend URL
7. ✅ Update `FRONTEND_URL` in Railway
8. ✅ Test full stack

### Updating Backend

```powershell
cd C:\shifa-doctor-backend
git add .
git commit -m "Your changes"
git push origin main
# Railway auto-deploys
```

### Updating Frontend

```powershell
cd C:\shifa_doc_app_v1
.\scripts\build_staging.bat https://your-app.railway.app AIzaSyYourGoogleMapsKey
firebase deploy --only hosting
```

---

## Success Checklist

- [ ] Backend deployed to Railway
- [ ] Backend accessible at Railway URL
- [ ] Health check works: `https://your-app.railway.app/actuator/health`
- [ ] All environment variables set in Railway
- [ ] Frontend built successfully
- [ ] Frontend deployed to Firebase
- [ ] Frontend accessible at Firebase URL
- [ ] Sign-in works
- [ ] API calls work (no CORS errors)
- [ ] File uploads work
- [ ] Images load correctly

---

## Support

- **Railway Docs**: https://docs.railway.app
- **Railway Discord**: https://discord.gg/railway
- **Firebase Docs**: https://firebase.google.com/docs/hosting
- **Flutter Docs**: https://flutter.dev/docs

---

## Notes

- Railway provides $5 credit/month (enough for staging)
- Firebase Hosting free tier: 10GB storage, 360MB/day bandwidth
- Both services auto-scale based on usage
- Monitor usage in respective dashboards
