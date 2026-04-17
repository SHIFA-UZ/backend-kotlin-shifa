# File Storage Implementation Summary

## ✅ What Was Changed

### 1. Updated StaticResourceConfig
- **Before**: Hardcoded file paths (Windows-specific)
- **After**: Dynamic paths using `AppProperties.storageRoot`
- **Result**: Works on any platform (Windows, Linux, Railway)

### 2. File Serving
- **Before**: Separate nginx server on port 8090
- **After**: Files served directly from Spring Boot
- **Result**: Simpler deployment, no separate service needed

### 3. Railway Volume Support
- **Before**: Local filesystem only
- **After**: Supports Railway Volumes for persistent storage
- **Result**: Files persist across deployments

## 📁 File Structure

Files are organized under `STORAGE_ROOT`:

```
/app/storage/images/          (or ./public-storage/images/ in dev)
├── doctors/                  → Served at /doctors/{filename}
├── patients/                 → Served at /patients/{filename}
├── patientdocuments/         → Served at /patientdocuments/{patientId}/{filename}
│   └── {patientId}/
│       └── *.pdf
└── certificates/             → Served at /certificates/{filename}
```

## 🔗 URL Structure

Files are served from your main Railway app domain:

| File Type | URL Example |
|-----------|-------------|
| Doctor Photo | `https://your-app.railway.app/doctors/nodir-malikov-3.jpg` |
| Patient Photo | `https://your-app.railway.app/patients/test-patient-47.jpg` |
| Patient Document | `https://your-app.railway.app/patientdocuments/47/form-025.pdf` |
| Certificate | `https://your-app.railway.app/certificates/cert-123.png` |

## ⚙️ Configuration

### Development (Local)
```yaml
app:
  publicBaseUrl: http://localhost:8080
  storageRoot: ./public-storage/images
```

### Staging (Railway)
```yaml
app:
  publicBaseUrl: https://your-app.railway.app
  storageRoot: /app/storage/images  # Railway volume mount
```

## 🚀 Deployment Steps

1. **Add Railway Volume**
   - Mount path: `/app/storage`
   - Size: 1GB+ (adjust as needed)

2. **Set Environment Variables**
   ```
   STORAGE_ROOT=/app/storage/images
   PUBLIC_BASE_URL=https://your-app.railway.app
   ```

3. **Deploy**
   - Files will be automatically saved to volume
   - Files will be served from Spring Boot

## ✅ Benefits

1. **No Separate Service** - No need for nginx file server
2. **Persistent Storage** - Railway Volumes keep files safe
3. **Simple URLs** - Files served from same domain as API
4. **Platform Agnostic** - Works on Windows, Linux, Railway
5. **Easy Migration** - Ready to migrate to S3 later

## 🔄 Migration Path

**Current (Staging)**: Railway Volumes ✅
**Future (Production)**: AWS S3 (see `PRODUCTION_STORAGE_REMINDER.md`)

## 📝 Testing

After deployment, test:

1. **Upload a file** (e.g., doctor photo)
2. **Check response** - Should return URL like:
   ```
   https://your-app.railway.app/doctors/photo.jpg
   ```
3. **Access file** - Open URL in browser, should see image
4. **Verify persistence** - Redeploy, file should still be accessible

## 🆘 Troubleshooting

**Files not accessible?**
- Check `STORAGE_ROOT` is set correctly
- Verify volume is mounted
- Check file exists in volume
- Verify `PUBLIC_BASE_URL` matches your Railway URL

**Files not persisting?**
- Verify Railway Volume is attached
- Check volume mount path matches `STORAGE_ROOT`
- Check Railway logs for errors

**Permission errors?**
- Railway handles permissions automatically
- If issues, check Railway logs

## 📚 Related Documentation

- **Volume Setup**: `RAILWAY_VOLUME_SETUP.md`
- **Production Migration**: `PRODUCTION_STORAGE_REMINDER.md`
- **Railway Deployment**: `RAILWAY_DEPLOYMENT.md`
