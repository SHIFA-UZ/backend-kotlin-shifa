# Fix "Failed to fetch" Error After Deployment

## Problem
Frontend deployed to Firebase (`https://shifa-doctor-staging.web.app`) cannot connect to backend at `https://www.shifa-doc-backend-mvp-production.up.railway.app`

Error: `Failed to fetch, uri=https://www.shifa-doc-backend-mvp-production.up.railway.app/api/auth/login`

## Solution Steps

### Step 1: Update Railway Environment Variables

Go to Railway → Your Backend Service → **Variables** tab and ensure these are set:

1. **`APP_FRONTENDURL`** = `https://shifa-doctor-staging.web.app`
   - This tells the backend to allow CORS requests from your Firebase domain

2. **`APP_PUBLICBASEURL`** = `https://www.shifa-doc-backend-mvp-production.up.railway.app`
   - This should match your Railway backend URL exactly

### Step 2: Verify Backend is Running

1. Go to Railway dashboard
2. Check your backend service status
3. Look at the latest deployment logs
4. Verify no errors in startup

### Step 3: Test Backend Directly

Open in browser:
```
https://www.shifa-doc-backend-mvp-production.up.railway.app/actuator/health
```

Should return: `{"status":"UP"}`

### Step 4: Check CORS Configuration

The backend should automatically allow:
- `https://*.web.app` (Firebase)
- `https://*.firebaseapp.com` (Firebase)
- Your specific `FRONTEND_URL` if set

### Step 5: Redeploy Backend (if needed)

After updating environment variables:
1. Railway will auto-redeploy
2. Wait for deployment to complete (~2-3 minutes)
3. Check deployment logs for any errors

### Step 6: Verify Frontend Build

Make sure you built the frontend with the correct backend URL:

```powershell
cd C:\shifa_doc_app_v1
.\scripts\build_staging.bat https://www.shifa-doc-backend-mvp-production.up.railway.app
```

**Important:** Do NOT include `/api` in the URL - the script handles that.

### Step 7: Test Again

1. Clear browser cache (Ctrl+Shift+Delete)
2. Hard refresh (Ctrl+F5)
3. Try login again

## Common Issues

### Issue 1: CORS Still Blocking

**Solution:**
- Double-check `APP_FRONTENDURL` in Railway matches your Firebase URL exactly
- Wait for Railway to redeploy after variable change
- Check backend logs for CORS errors

### Issue 2: Backend Not Responding

**Solution:**
- Check Railway service is running
- Check deployment logs for errors
- Verify `DATABASE_URL` is set correctly
- Check `JWT_SECRET` is set

### Issue 3: Wrong Backend URL in Frontend

**Solution:**
- Rebuild frontend with correct URL:
  ```powershell
  .\scripts\build_staging.bat https://www.shifa-doc-backend-mvp-production.up.railway.app
  ```
- Redeploy to Firebase:
  ```powershell
  firebase deploy --only hosting
  ```

### Issue 4: SSL/Certificate Issues

**Solution:**
- Railway should handle SSL automatically
- If issues persist, check Railway networking settings
- Verify custom domain is configured correctly

## Quick Fix Checklist

- [ ] `APP_FRONTENDURL` set in Railway to `https://shifa-doctor-staging.web.app`
- [ ] `APP_PUBLICBASEURL` set in Railway to your Railway URL
- [ ] Backend service is running in Railway
- [ ] Health check works: `/actuator/health` returns `{"status":"UP"}`
- [ ] Frontend built with correct backend URL
- [ ] Frontend redeployed to Firebase
- [ ] Browser cache cleared
- [ ] Tested login again

## Still Not Working?

1. **Check Browser Console (F12)**
   - Look for detailed error messages
   - Check Network tab for failed requests
   - Look for CORS error messages

2. **Check Railway Logs**
   - Go to Railway → Your Service → Deployments
   - Click latest deployment → View logs
   - Look for CORS or connection errors

3. **Test Backend API Directly**
   - Use Postman or curl to test:
   ```
   POST https://www.shifa-doc-backend-mvp-production.up.railway.app/api/auth/login
   Content-Type: application/json
   
   {
     "email": "test@example.com",
     "password": "test"
   }
   ```

4. **Verify Environment Variables**
   - Railway → Variables tab
   - Ensure all required variables are set
   - No typos in URLs
