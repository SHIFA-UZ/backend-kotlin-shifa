# Quick Deployment Commands

Copy and paste these commands for deployment.

---

## 🚀 Backend Deployment (Railway)

### Initial Setup (One-time)

1. **Push code to GitHub:**
```powershell
cd C:\shifa-doctor-backend
git add .
git commit -m "Prepare for Railway deployment"
git push origin main
```

2. **Create Railway project:**
   - Go to https://railway.app
   - Click "New Project" → "Deploy from GitHub repo"
   - Select your repository

3. **Add PostgreSQL:**
   - In Railway project, click "+ New" → "Database" → "Add PostgreSQL"

4. **Add Volume (Optional):**
   - Settings → Volumes → "+ New Volume"
   - Name: `file-storage`, Mount: `/app/storage`, Size: 1GB

5. **Set Environment Variables in Railway:**
   - Go to Variables tab
   - Add these variables:

```
SPRING_PROFILES_ACTIVE=prod
DATABASE_URL=${{Postgres.DATABASE_URL}}
JWT_SECRET=<generate-with-command-below>
JWT_ISSUER=shifa
PUBLIC_BASE_URL=https://your-app.railway.app
FRONTEND_URL=https://your-firebase-app.web.app
STORAGE_ROOT=/app/storage/images
OPENAI_API_KEY=sk-...
OPENAI_PROJECT_ID=proj_...
```

6. **Generate JWT Secret:**
```powershell
[Convert]::ToBase64String((1..64 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
```

7. **After deployment, get your Railway URL:**
   - Settings → Networking → Public Domain
   - Update `PUBLIC_BASE_URL` with this URL

### Update Backend

```powershell
cd C:\shifa-doctor-backend
git add .
git commit -m "Your changes"
git push origin main
# Railway auto-deploys
```

---

## 🌐 Frontend Deployment (Firebase)

### Initial Setup (One-time)

1. **Install Firebase CLI:**
```powershell
npm install -g firebase-tools
```

2. **Login to Firebase:**
```powershell
firebase login
```

3. **Create Firebase Project:**
   - Go to https://console.firebase.google.com/
   - Create project: `shifa-doctor-staging`

4. **Initialize Firebase:**
```powershell
cd C:\shifa_doc_app_v1
firebase init hosting
```
   - Select "Hosting"
   - Use existing project
   - Public directory: `build/web`
   - Single-page app: Yes
   - GitHub auto-deploy: No

### Build and Deploy

**Replace `https://your-app.railway.app` with your actual Railway URL:**

```powershell
cd C:\shifa_doc_app_v1
.\scripts\build_staging.bat https://your-app.railway.app
firebase deploy --only hosting
```

**Example:**
```powershell
.\scripts\build_staging.bat https://shifa-backend.railway.app
firebase deploy --only hosting
```

### Update Frontend

```powershell
cd C:\shifa_doc_app_v1
.\scripts\build_staging.bat https://your-app.railway.app
firebase deploy --only hosting
```

---

## ✅ Verification

### Test Backend
```powershell
# Health check
curl https://your-app.railway.app/actuator/health

# Or visit in browser:
https://your-app.railway.app/actuator/health
```

### Test Frontend
- Visit your Firebase URL: `https://shifa-doctor-staging.web.app`
- Try to sign in
- Check browser console (F12) for errors

---

## 🔧 Troubleshooting Commands

### Check Railway Status
- Visit Railway dashboard → Your Service → Deployments

### Check Firebase Status
```powershell
firebase projects:list
firebase use staging
```

### Rebuild Frontend
```powershell
cd C:\shifa_doc_app_v1
flutter clean
flutter pub get
.\scripts\build_staging.bat https://your-app.railway.app
```

---

## 📝 Environment Variables Reference

### Railway Variables (Backend)

| Variable | Value | Notes |
|----------|-------|-------|
| `SPRING_PROFILES_ACTIVE` | `prod` | Required |
| `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` | Auto-set by Railway |
| `JWT_SECRET` | `<64+ chars>` | Generate with PowerShell command |
| `JWT_ISSUER` | `shifa` | Required |
| `PUBLIC_BASE_URL` | `https://your-app.railway.app` | Your Railway URL |
| `FRONTEND_URL` | `https://your-firebase-app.web.app` | Your Firebase URL |
| `STORAGE_ROOT` | `/app/storage/images` | File storage path |
| `OPENAI_API_KEY` | `sk-...` | Your OpenAI key |
| `OPENAI_PROJECT_ID` | `proj_...` | Your OpenAI project ID |

---

## 🎯 Complete Deployment Flow

1. **Deploy Backend:**
   ```powershell
   cd C:\shifa-doctor-backend
   git push origin main
   ```
   - Wait for Railway deployment (~3-5 min)
   - Get Railway URL from Settings → Networking

2. **Update Backend Variables:**
   - Set `PUBLIC_BASE_URL` to your Railway URL

3. **Build Frontend:**
   ```powershell
   cd C:\shifa_doc_app_v1
   .\scripts\build_staging.bat https://your-app.railway.app
   ```

4. **Deploy Frontend:**
   ```powershell
   firebase deploy --only hosting
   ```

5. **Update Backend CORS:**
   - Set `FRONTEND_URL` in Railway to your Firebase URL
   - Railway auto-redeploys

6. **Test:**
   - Visit Firebase URL
   - Sign in
   - Verify API calls work

---

## 📚 Full Documentation

See `DEPLOYMENT_GUIDE.md` for detailed step-by-step instructions.
