# CORS Fix for Firebase Frontend

## Issue
"Failed to fetch" error when frontend tries to connect to backend.

## Solution: Update Railway Environment Variables

The backend needs to know your Firebase frontend URL to allow CORS requests.

### Step 1: Go to Railway Dashboard
1. Go to: https://railway.app
2. Click on your backend service
3. Go to **"Variables"** tab

### Step 2: Add/Update PUBLIC_BASE_URL
Add or update this environment variable:

```
PUBLIC_BASE_URL=https://shifa-doctor-staging.web.app
```

**Important:** This should be your **Firebase frontend URL**, not your Railway backend URL!

### Step 3: Redeploy (if needed)
Railway should automatically redeploy when you update environment variables. If not:
1. Go to **"Deployments"** tab
2. Click **"Redeploy"** on the latest deployment

## Verify CORS is Working

After updating, the backend will:
- Allow requests from `https://shifa-doctor-staging.web.app`
- Allow requests from all `*.web.app` domains
- Allow requests from all `*.firebaseapp.com` domains

## Test

1. Clear browser cache (Ctrl+Shift+Delete)
2. Hard refresh (Ctrl+F5)
3. Try logging in again

## Current CORS Configuration

The backend already allows:
- `https://*.web.app` (Firebase Hosting)
- `https://*.firebaseapp.com` (Firebase Hosting)
- `https://*.railway.app` (Railway)
- `https://*.netlify.app` (Netlify)
- `https://*.vercel.app` (Vercel)

So even without `PUBLIC_BASE_URL`, it should work. But setting it explicitly ensures your specific domain is allowed.
