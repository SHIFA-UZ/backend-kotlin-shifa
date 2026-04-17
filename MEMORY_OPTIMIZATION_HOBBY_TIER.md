# Memory Optimization for Railway Hobby Tier

## Railway Hobby Tier Limits
- **Maximum RAM per service**: 8GB
- **Billing**: Pay for actual usage (included $5/month, overage charged)
- **Memory available**: Up to 8GB per service

## Current Configuration

### JVM Heap Settings
- **Max Heap**: 1.5GB (`-Xmx1536m`)
- **Initial Heap**: 512MB (`-Xms512m`)
- **Metaspace**: 384MB (`-XX:MaxMetaspaceSize=384m`)

### Memory Breakdown
- **JVM Heap**: 1.5GB (max)
- **Metaspace**: 384MB (max)
- **Container Overhead**: ~200-300MB
- **Total**: ~2.1GB (well within 8GB limit)

### Garbage Collection
- **GC**: G1GC (`-XX:+UseG1GC`)
- **Max GC Pause**: 200ms (`-XX:MaxGCPauseMillis=200`)
- **String Deduplication**: Enabled (`-XX:+UseStringDeduplication`)

## Optimizations Applied

### 1. Removed Excessive Logging
- ✅ Removed all `println()` from `SecurityConfig.kt`
- ✅ Removed most `println()` from `AuthController.kt`
- ✅ Removed all `log.debug()` from `JwtAuthFilter.kt`
- ✅ Set `com.shifa.security: WARN` in production

### 2. Memory Settings
- ✅ 1.5GB heap (leaves plenty of room for growth)
- ✅ 384MB metaspace limit (prevents class metadata leaks)
- ✅ String deduplication (saves memory on duplicate strings)
- ✅ G1GC for efficient garbage collection

### 3. Logging Configuration
- ✅ Reduced log file history from 30 to 10 files
- ✅ Set appropriate log levels for production
- ✅ Reduced Tomcat logging verbosity

## Why 1.5GB?

- **Safe margin**: Well below 8GB limit
- **Room for growth**: Can handle more concurrent requests
- **Container overhead**: Leaves ~6.5GB for other processes if needed
- **Cost effective**: Uses only what's needed, not the full 8GB

## Monitoring

After deployment, check:
1. Railway metrics dashboard for actual memory usage
2. Application logs for GC activity
3. No OutOfMemoryError
4. Stable memory usage over time

## If Issues Persist

If you still see OutOfMemoryError with 1.5GB:
1. **Check actual usage**: Look at Railway metrics to see peak usage
2. **Increase gradually**: Try `-Xmx2048m` (2GB) if needed
3. **Profile application**: Use JVM profiling tools to find leaks
4. **Check for memory leaks**: Review code for objects not being released

## Cost Considerations

- **1.5GB usage**: ~$0.15-0.30/month (well within $5 included)
- **2GB usage**: ~$0.20-0.40/month (still within $5)
- **Monitor usage**: Check Railway billing dashboard
