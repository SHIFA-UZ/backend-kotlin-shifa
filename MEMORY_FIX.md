# Memory Issue Fix

## Problem
Backend was experiencing `OutOfMemoryError: Java heap space` due to:
1. **Insufficient heap size**: Only 350MB allocated
2. **Excessive DEBUG logging**: JwtAuthFilter was logging DEBUG for every request, creating thousands of log entries

## Solution Applied

### 1. Increased JVM Heap Size
- **Before**: `-Xmx350m -Xms256m` (350MB max, 256MB initial)
- **After**: `-Xmx1024m -Xms512m` (1GB max, 512MB initial)
- Added G1GC garbage collector for better memory management

### 2. Disabled Excessive Debug Logging
- Removed all `log.debug()` calls from `JwtAuthFilter`
- Changed to `log.trace()` for detailed logging (only enabled if needed)
- Updated `application-prod.yml` to set `com.shifa.security: WARN`

### 3. Files Changed
- `railway.toml` - Updated startCommand with new heap size
- `railway.json` - Updated startCommand with new heap size
- `application-prod.yml` - Added explicit WARN level for security package
- `JwtAuthFilter.kt` - Removed debug logging statements

## Deployment

After pushing these changes, Railway will:
1. Rebuild the application
2. Start with 1GB heap instead of 350MB
3. Use G1GC garbage collector for better performance
4. Stop generating excessive debug logs

## Monitoring

Watch Railway logs after deployment to ensure:
- No more OutOfMemoryError
- Application starts successfully
- Memory usage is stable

## If Issues Persist

If you still see memory issues:
1. Check Railway service limits (may need to upgrade plan)
2. Review other sources of memory consumption
3. Consider further reducing logging verbosity
4. Check for memory leaks in application code
