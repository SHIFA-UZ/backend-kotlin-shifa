# Railway Volume Setup for File Storage

This guide explains how to set up persistent file storage using Railway Volumes for your staging deployment.

## Overview

Instead of using a separate nginx file server, we now serve files directly from Spring Boot using Railway Volumes for persistent storage.

## Why Railway Volumes?

- ✅ **Persistent** - Files survive deployments and restarts
- ✅ **Simple** - No separate service needed
- ✅ **Integrated** - Works seamlessly with Railway
- ✅ **Free Tier** - Included in Railway's free tier

## Step-by-Step Setup

### Step 1: Add Volume to Your Railway Service

1. Go to your Railway project dashboard
2. Click on your backend service
3. Go to **"Settings"** tab
4. Scroll down to **"Volumes"** section
5. Click **"+ New Volume"**
6. Configure:
   - **Name**: `file-storage` (or any name you prefer)
   - **Mount Path**: `/app/storage`
   - **Size**: Start with 1GB (you can increase later)
7. Click **"Add"**

### Step 2: Set Environment Variables

In your Railway service → **"Variables"** tab, add/update:

```
STORAGE_ROOT=/app/storage/images
PUBLIC_BASE_URL=https://your-app-name.railway.app
```

**Important Notes:**
- `STORAGE_ROOT` must match the volume mount path + `/images`
- `PUBLIC_BASE_URL` should be your Railway app URL (not localhost:8090)
- Files will be served from the same domain as your API

### Step 3: Create Directory Structure

The app will automatically create directories, but you can verify:

After first deployment, the volume should have:
```
/app/storage/
  └── images/
      ├── doctors/
      ├── patients/
      ├── patientdocuments/
      └── certificates/
```

### Step 4: Deploy

1. Push your code to GitHub (Railway auto-deploys)
2. Or manually trigger deployment
3. Wait for deployment to complete

### Step 5: Verify File Serving

Test file upload and serving:

1. **Upload a file** via your API (e.g., doctor photo)
2. **Check the response** - should return URL like:
   ```
   https://your-app.railway.app/doctors/photo.jpg
   ```
3. **Access the file** directly:
   ```
   https://your-app.railway.app/doctors/photo.jpg
   ```

## File URL Structure

Files are now served from your main Railway app domain:

| File Type | URL Pattern | Storage Path |
|-----------|-------------|--------------|
| Doctor Photos | `https://your-app.railway.app/doctors/{filename}` | `/app/storage/images/doctors/` |
| Patient Photos | `https://your-app.railway.app/patients/{filename}` | `/app/storage/images/patients/` |
| Patient Documents | `https://your-app.railway.app/patientdocuments/{patientId}/{filename}` | `/app/storage/images/patientdocuments/{patientId}/` |
| Certificates | `https://your-app.railway.app/certificates/{filename}` | `/app/storage/images/certificates/` |

## Migration from Local Storage

If you have existing files in `public-storage/images/`:

### Option A: Upload via API (Recommended)
- Files will be automatically saved to Railway volume
- No manual migration needed

### Option B: Manual Migration (If needed)
1. Download files from local `public-storage/images/`
2. Use Railway CLI or dashboard to upload to volume
3. Or use Railway's file browser (if available)

## Troubleshooting

### Files Not Persisting

**Problem:** Files disappear after deployment

**Solution:**
- Verify volume is mounted correctly
- Check `STORAGE_ROOT` environment variable
- Ensure volume is attached to your service

### Files Not Accessible

**Problem:** 404 when accessing file URLs

**Solution:**
- Check `PUBLIC_BASE_URL` is set correctly
- Verify file exists in volume: Check Railway logs
- Check CORS configuration allows your frontend domain
- Verify `StaticResourceConfig` is serving from correct path

### Permission Errors

**Problem:** Cannot write to volume

**Solution:**
- Railway volumes should have correct permissions automatically
- If issues persist, check Railway logs for permission errors
- Contact Railway support if needed

### Volume Full

**Problem:** Out of storage space

**Solution:**
1. Go to Railway → Your Service → Settings → Volumes
2. Click on your volume
3. Increase size (may require upgrade)
4. Or clean up old files via API

## Monitoring Storage Usage

Railway dashboard shows:
- Volume size
- Used space
- Available space

Monitor in: **Service → Settings → Volumes**

## Best Practices

1. **Regular Backups** - Export important files periodically
2. **Clean Up** - Remove unused files to save space
3. **Monitor Usage** - Check volume size regularly
4. **Set Limits** - Configure appropriate volume size

## Production Migration (Future)

When moving to production, consider:
- **AWS S3** - Scalable cloud storage
- **Google Cloud Storage** - Alternative cloud option
- **CDN** - For faster file delivery globally

See: `PRODUCTION_STORAGE_MIGRATION.md` (to be created)

## Environment Variables Summary

```bash
# Required for file storage
STORAGE_ROOT=/app/storage/images
PUBLIC_BASE_URL=https://your-app-name.railway.app

# Other required variables (from main deployment guide)
SPRING_PROFILES_ACTIVE=prod
DATABASE_URL=...
JWT_SECRET=...
# ... etc
```

## Quick Reference

```bash
# Check volume is mounted (in Railway logs)
ls -la /app/storage/images

# Verify environment variables
echo $STORAGE_ROOT
echo $PUBLIC_BASE_URL

# Test file upload
curl -X POST https://your-app.railway.app/api/doctors/me/photo \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F "file=@photo.jpg"
```

## Support

- Railway Volumes Docs: https://docs.railway.app/storage/volumes
- Railway Discord: https://discord.gg/railway
