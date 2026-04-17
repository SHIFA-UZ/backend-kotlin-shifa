# Daily Flutter Web Support Fix

## Problem

`daily_flutter` package **does NOT support web**. It only works on:
- ✅ Android
- ✅ iOS  
- ✅ Windows (desktop)
- ✅ macOS (desktop)
- ✅ Linux (desktop)
- ❌ **Web (Flutter Web) - NOT SUPPORTED**

When you run `flutter run -d edge`, you're targeting web, which causes FFI errors because `dart:ffi` is not available on web.

## Solution

### Option 1: Test on Mobile/Desktop (Recommended)

**For testing, use Android or iOS instead of web:**

```bash
# List available devices
flutter devices

# Run on Android emulator/device
flutter run -d android

# Run on iOS simulator/device  
flutter run -d ios

# Run on Windows desktop
flutter run -d windows
```

### Option 2: Web Implementation (Separate)

For web support, you need to use Daily.co's **Prebuilt** solution or their **JavaScript SDK**:

1. **Daily.co Prebuilt** - Embed an iframe
2. **Daily.co JavaScript SDK** - Use JS interop

The current implementation I've updated will:
- **Mobile/Desktop**: Use `daily_flutter` (CallClient)
- **Web**: Open Daily.co Prebuilt in a new window (temporary solution)

## Quick Fix

**To test video calls right now:**

1. **Don't use web** - Use Android/iOS instead
2. Run: `flutter run -d android` or `flutter run -d ios`
3. Video calls will work on mobile/desktop platforms

## Web Implementation (Future)

For full web support, you would need to:
1. Use Daily.co's JavaScript SDK via JS interop
2. Or embed Daily.co Prebuilt iframe directly
3. Or create a separate web route that redirects to Daily.co Prebuilt

---

**Current Status:**
- ✅ Backend: Ready
- ✅ Mobile (Android/iOS): Ready (with daily_flutter)
- ⚠️ Web: Limited (opens in new window)

**Recommendation:** Test on Android/iOS first, then we can implement proper web support if needed.
