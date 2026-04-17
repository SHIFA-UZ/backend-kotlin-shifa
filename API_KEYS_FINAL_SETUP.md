# API Keys - Final Setup Guide

## ✅ What's Been Done

All API keys are now configured to work seamlessly with your Railway environment variables.

## Railway Variables (Already Set)

You've already set these in Railway:
- ✅ `GOOGLE_MAPS_API_KEY`
- ✅ `DAILY_API_URL`
- ✅ `DAILY_API_KEY`

## How It Works Now

### Backend
- **Daily.co:** Uses `DAILY_API_KEY` and `DAILY_API_URL` directly from Railway ✅
- **Google Maps:** Reads `GOOGLE_MAPS_API_KEY` from Railway and exposes it via `/api/public/config` endpoint ✅

### Frontend
- **Google Maps:** Automatically fetches API key from backend at runtime (no build-time key needed) ✅
- Falls back to build-time key if provided
- Caches the key after first fetch for performance

## Code Changes Made

### Backend
1. ✅ `application-prod.yml` - Added Daily.co and Google Maps config sections
2. ✅ `ConfigController.kt` - New endpoint `/api/public/config` that returns Google Maps API key

### Frontend
1. ✅ `google_geocoding_service.dart` - Now fetches API key from backend if not set at build time
2. ✅ `app_config.dart` - Added `googleMapsApiKey` property
3. ✅ `build_staging.bat` - Google Maps API key is now optional (can be fetched from backend)

## Deployment

### Backend
Just push the code - Railway variables are already set:
```powershell
cd C:\shifa-doctor-backend
git add .
git commit -m "Add config endpoint for Google Maps API key"
git push origin main
```

### Frontend
Build **without** Google Maps API key (it will fetch from backend):
```powershell
cd C:\shifa_doc_app_v1
.\scripts\build_staging.bat https://your-railway-url.railway.app
firebase deploy --only hosting
```

**Or** build with Google Maps API key (optional, for faster first load):
```powershell
.\scripts\build_staging.bat https://your-railway-url.railway.app AIzaSyYourKey
firebase deploy --only hosting
```

## Verification

### Test Backend Config Endpoint
```bash
curl https://your-railway-url.railway.app/api/public/config
```

Should return:
```json
{
  "googleMapsApiKey": "AIzaSy..."
}
```

### Test Frontend
1. Open app in browser
2. Use location picker
3. Check browser console - should see: "Google Maps API key fetched from backend"

## Benefits

✅ **No build-time keys needed** - Frontend fetches from backend
✅ **Centralized in Railway** - All keys in one place
✅ **Easy updates** - Change keys in Railway, no rebuild needed
✅ **Secure** - Keys not in frontend build artifacts

## Current Railway Variables

Make sure these are set in Railway:

| Variable | Status | Purpose |
|----------|--------|---------|
| `GOOGLE_MAPS_API_KEY` | ✅ You set this | Exposed to frontend via config endpoint |
| `DAILY_API_KEY` | ✅ You set this | Used by backend for video calls |
| `DAILY_API_URL` | ✅ You set this | Daily.co API URL (default: https://api.daily.co/v1) |

Everything is ready to deploy! 🚀
