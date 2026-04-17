# Deployment Fix: Images and Patient Documents

I have updated the backend to fix the issues with images not loading and patient documents missing.

## 1. Fix: Patient Documents
Added `Hibernate.initialize(p.documents)` to ensure documents are loaded before the database connection closes. This fixes the "no Session" error for documents.

## 2. Fix: Images (CORS and URL separation)
I have separated the **Backend URL** (used for images) from the **Frontend URL** (used for CORS security).

### Step 1: Update Railway Environment Variables
Go to your Railway Dashboard and update the **Variables** tab for your backend service:

| Variable | Value | Description |
|----------|-------|-------------|
| **`APP_PUBLICBASEURL`** | `https://shifa-doc-backend-mvp-production.up.railway.app` | **Your Railway URL**. Used for image paths. |
| **`APP_FRONTENDURL`** | `https://shifa-doctor-staging.web.app` | **Your Firebase URL**. Used for CORS security. |

*Note: Spring Boot translates `APP_PUBLICBASEURL` to `app.publicBaseUrl` automatically.*

### Step 2: Push Changes and Redeploy
1. Switch to **Agent Mode** (if not already).
2. Push the latest code changes to your repository.
3. Wait for Railway to finish the deployment (Status: **Active**).

## 3. Why images weren't loading
1. **Security**: Static files were blocked by Spring Security. I've now made them public.
2. **URL Mismatch**: If you set `PUBLIC_BASE_URL` to your Firebase URL, the app was trying to find images on Firebase instead of Railway. By separating them, images will now correctly point to Railway.

## 4. Test
Once the deployment is **Active**:
1. Open your app: [https://shifa-doctor-staging.web.app](https://shifa-doctor-staging.web.app)
2. **Hard Refresh** (Ctrl + F5).
3. Log in.
4. **Check Patients**: Documents should now appear.
5. **Check Photos**: Try uploading a new photo. It should appear immediately.
