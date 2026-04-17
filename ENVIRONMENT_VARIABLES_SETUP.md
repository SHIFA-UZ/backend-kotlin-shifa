# Environment Variables Setup for Railway

## Summary

All API keys and sensitive configuration are now managed via Railway environment variables.

## Railway Environment Variables

Set these in Railway → Your Service → **Variables** tab:

### Required Variables

| Variable | Description | Example/Notes |
|----------|-------------|---------------|
| `SPRING_PROFILES_ACTIVE` | Spring profile | `prod` |
| `DATABASE_URL` | PostgreSQL connection | `${{Postgres.DATABASE_URL}}` (auto-set by Railway) |
| `JWT_SECRET` | JWT signing secret | Generate 64+ character secret |
| `JWT_ISSUER` | JWT issuer | `shifa` |
| `APP_PUBLICBASEURL` | Your Railway backend URL | `https://your-app.railway.app` |
| `APP_FRONTENDURL` | Your Firebase frontend URL | `https://shifa-doctor-staging.web.app` |
| `STORAGE_ROOT` | File storage path | `/app/storage/images` |
| `OPENAI_API_KEY` | OpenAI API key | `sk-...` |
| `OPENAI_PROJECT_ID` | OpenAI project ID | `proj_...` |
| `DAILY_API_KEY` | Daily.co API key for video calls | Get from https://dashboard.daily.co/ |
| `DAILY_API_URL` | Daily.co API URL | `https://api.daily.co/v1` (default) |
| `GOOGLE_MAPS_API_KEY` | Google Maps API key | Get from https://console.cloud.google.com/google/maps-apis |
| `GOOGLE_APPLICATION_CREDENTIALS` | **Firebase (local):** Full path to Firebase service account JSON file | Optional if `FIREBASE_SERVICE_ACCOUNT_JSON` is set. Use for local runs. |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | **Firebase (Railway):** Entire contents of the Firebase service account JSON (single line or multiline) | **Use this on Railway.** Firebase Console → Project settings → Service accounts → Generate new private key. Copy the whole JSON and paste as the variable value in Railway. The backend writes it to a temp file and uses it for `/api/auth/verify` and `/api/auth/forgot-password-reset`. |

### Database: Railway vs local

- **Railway:** Add a Postgres service and link it to your backend. Railway sets `DATABASE_URL` (e.g. `postgresql://user:pass@host:port/dbname`). The app’s `Application.kt` converts this to JDBC and overrides any YAML DB config. You do **not** need to set `DB_PORT`, `DB_USERNAME`, or `DB_PASSWORD` on Railway.
- **Local:** Default config uses `localhost:5433` (Docker Postgres from docker-compose). Use `DB_PORT=5432` and `DB_USERNAME`/`DB_PASSWORD` if you use native Postgres instead. Local settings do **not** affect Railway.

### How It Works

1. **Backend (Railway):**
   - `DAILY_API_KEY` and `DAILY_API_URL` are used directly by the backend for video calls
   - `GOOGLE_MAPS_API_KEY` is exposed to frontend via `/api/public/config` endpoint

2. **Frontend:**
   - Google Maps API key is fetched from backend at runtime (no build-time key needed)
   - Falls back to build-time key if backend doesn't provide it
   - Cached after first fetch for performance

## Setup Steps

### 1. Add Variables to Railway

Go to Railway → Your Service → **Variables** and add:

```
DAILY_API_KEY=your_daily_co_key_here
DAILY_API_URL=https://api.daily.co/v1
GOOGLE_MAPS_API_KEY=your_google_maps_key_here
```

**Firebase (Phone OTP login):** Add `FIREBASE_SERVICE_ACCOUNT_JSON` with the **entire** Firebase service account JSON (from Firebase Console → Project settings → Service accounts → Generate new private key). Paste the full JSON as the value (single line or multiline). The backend initializes Firebase from this variable directly (no file path needed); do not set `GOOGLE_APPLICATION_CREDENTIALS` on Railway. If you see "Phone verification not configured", the backend could not read this variable or the JSON is invalid—check the variable name and that the full JSON is pasted (no truncation).

### 2. Deploy Backend

After adding variables, Railway will auto-redeploy. The backend will:
- Use `DAILY_API_KEY` for video calls
- Expose `GOOGLE_MAPS_API_KEY` via `/api/public/config` endpoint

### 3. Build Frontend

You can now build the frontend **without** passing the Google Maps API key:

```powershell
cd C:\shifa_doc_app_v1
.\scripts\build_staging.bat https://your-railway-url.railway.app
```

The frontend will automatically fetch the Google Maps API key from the backend at runtime.

**Optional:** You can still pass the Google Maps API key at build time if you want:
```powershell
.\scripts\build_staging.bat https://your-railway-url.railway.app AIzaSyYourKey
```

## Verification

### Test Backend Config Endpoint

After deployment, test the config endpoint:

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

1. Deploy frontend to Firebase
2. Open the app in browser
3. Try using the location picker
4. Check browser console - should see: "Google Maps API key fetched from backend"

## Benefits

✅ **Centralized Configuration:** All API keys in one place (Railway)
✅ **No Build-Time Keys:** Frontend doesn't need Google Maps key at build time
✅ **Easy Updates:** Change keys in Railway without rebuilding frontend
✅ **Security:** Keys not exposed in frontend build artifacts

## Troubleshooting

### Google Maps Not Working

1. Verify `GOOGLE_MAPS_API_KEY` is set in Railway
2. Test config endpoint: `https://your-backend.railway.app/api/public/config`
3. Check browser console for errors
4. Verify Google Maps API key has Geocoding API enabled

### Video Calls Not Working

1. Verify `DAILY_API_KEY` is set in Railway
2. Check Railway logs for Daily.co API errors
3. Verify Daily.co API key is valid

### Config Endpoint Returns Empty Key

- Check `GOOGLE_MAPS_API_KEY` is set in Railway variables
- Verify variable name is exactly `GOOGLE_MAPS_API_KEY` (case-sensitive)
- Check backend logs for any errors
