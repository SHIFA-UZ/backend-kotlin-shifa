# API Keys Migration to Environment Variables

## Summary

Both API keys have been migrated from hardcoded values to environment variables for security.

## Changes Made

### 1. Google Maps API Key (Frontend)

**Before:**
- Hardcoded in `lib/core/services/google_geocoding_service.dart`
- Value: `AIzaSyD8CSmFnNEcUH7JbA-QgRhbHiRBWTt0Jg4`

**After:**
- Uses `AppConfig.googleMapsApiKey` which reads from `GOOGLE_MAPS_API_KEY` environment variable
- Set at build time via `--dart-define=GOOGLE_MAPS_API_KEY=your_key_here`

**Files Changed:**
- `lib/core/services/google_geocoding_service.dart` - Now uses `AppConfig.googleMapsApiKey`
- `lib/core/config/app_config.dart` - Added `googleMapsApiKey` property
- `scripts/build_staging.bat` - Updated to accept Google Maps API key as second parameter

### 2. Daily.co API Key (Backend)

**Before:**
- Hardcoded default in `application.yml` (development)
- Value: `7e5614c8eb388445d63dec33b390e8cc9e396185517deff5f13be5ece7b5e714`

**After:**
- Production config (`application-prod.yml`) requires `DAILY_API_KEY` environment variable (no default)
- Development config (`application.yml`) still has default for local development

**Files Changed:**
- `src/main/resources/application-prod.yml` - Added Daily.co config section requiring `DAILY_API_KEY`

## Environment Variables Required

### Railway (Backend)

Add these to Railway → Variables:

| Variable | Description | Example |
|----------|-------------|---------|
| `DAILY_API_KEY` | Daily.co API key for video calls | `7e5614c8eb388445d63dec33b390e8cc9e396185517deff5f13be5ece7b5e714` |

**Note:** Get your Daily.co API key from: https://dashboard.daily.co/

### Frontend Build

When building the frontend, pass the Google Maps API key:

```powershell
cd C:\shifa_doc_app_v1
.\scripts\build_staging.bat https://your-railway-url.railway.app AIzaSyD8CSmFnNEcUH7JbA-QgRhbHiRBWTt0Jg4
```

**Parameters:**
1. `API_BASE_URL` - Your Railway backend URL (required)
2. `GOOGLE_MAPS_API_KEY` - Your Google Maps API key (optional, but required for geocoding)

**Note:** Get your Google Maps API key from: https://console.cloud.google.com/google/maps-apis

## Migration Steps

### Backend (Railway)

1. Go to Railway → Your Service → Variables
2. Add `DAILY_API_KEY` with your Daily.co API key value
3. Railway will auto-redeploy

### Frontend (Build Script)

1. Update your build command to include Google Maps API key:
   ```powershell
   .\scripts\build_staging.bat https://your-railway-url.railway.app AIzaSyD8CSmFnNEcUH7JbA-QgRhbHiRBWTt0Jg4
   ```
2. Rebuild and redeploy to Firebase

## Security Notes

✅ **Production:** Both API keys are now environment variables (not in code)
✅ **Development:** Daily.co key still has default in `application.yml` for local dev (can be overridden)
⚠️ **Google Maps:** No default value - must be provided at build time or geocoding will fail

## Verification

### Backend
- Check Railway logs after deployment
- Video calls should work if `DAILY_API_KEY` is set correctly

### Frontend
- Test location picker/geocoding functionality
- If geocoding fails, check browser console for API key errors
- Verify API key is included in build: Check `build/web/main.dart.js` (should contain the key)

## Troubleshooting

### Google Maps Geocoding Not Working
- Verify API key was passed to build script
- Check browser console for API key errors
- Verify Google Maps API key has Geocoding API enabled
- Check API key restrictions in Google Cloud Console

### Video Calls Not Working
- Verify `DAILY_API_KEY` is set in Railway
- Check Railway logs for Daily.co API errors
- Verify Daily.co API key is valid and has correct permissions

## Old API Keys (Remove from Code)

These keys should no longer be in the codebase:
- ❌ Google Maps: `AIzaSyD8CSmFnNEcUH7JbA-QgRhbHiRBWTt0Jg4` (removed from code)
- ⚠️ Daily.co: `7e5614c8eb388445d63dec33b390e8cc9e396185517deff5f13be5ece7b5e714` (still in `application.yml` as dev default, but not in production)
