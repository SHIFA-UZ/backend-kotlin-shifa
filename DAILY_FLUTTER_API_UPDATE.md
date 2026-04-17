# Daily Flutter API Update Guide

## Version Update
Updated from `daily_flutter: ^0.1.0` to `daily_flutter: ^0.37.0`

## API Changes

The `daily_flutter` package has changed its API. The old `DailyCallFrame` API has been replaced with `CallClient`.

### Old API (0.1.0 - doesn't exist)
- `DailyCallFrame.create()`
- `DailyCallFrameWidget`
- `DailyCallFrameOptions`

### New API (0.37.0)
- `CallClient()` - Main class for managing calls
- `CallClient.join()` - Join a call
- `CallClient.leave()` - Leave a call
- `CallClient.events` - Stream of events
- `VideoView` or custom video rendering

## Migration Steps

After running `flutter pub get`, you'll need to update the video call implementation to use the new API.

### Example New Implementation:

```dart
import 'package:daily_flutter/daily_flutter.dart';

class VideoCallScreen extends StatefulWidget {
  // ...
}

class _VideoCallScreenState extends State<VideoCallScreen> {
  CallClient? _callClient;
  bool _isVideoInitialized = false;
  bool _isMuted = false;
  bool _isVideoOff = false;
  
  @override
  void initState() {
    super.initState();
    _initializeVideoCall();
  }
  
  Future<void> _initializeVideoCall() async {
    try {
      // Get token from backend
      final tokenData = await _videoService!.getVideoToken(
        appointmentId: appointmentId,
      );
      
      // Create CallClient
      _callClient = CallClient();
      
      // Listen to events
      _callClient!.events.listen((event) {
        _handleCallEvent(event);
      });
      
      // Join the call
      await _callClient!.join(
        roomUrl: tokenData.roomUrl,
        token: tokenData.token,
      );
      
      setState(() {
        _isVideoInitialized = true;
      });
    } catch (e) {
      // Handle error
    }
  }
  
  void _handleCallEvent(CallEvent event) {
    // Handle events like CallStateUpdated, ParticipantJoined, etc.
  }
  
  Future<void> _toggleMute() async {
    if (_callClient != null) {
      await _callClient!.setLocalAudio(!_isMuted);
      setState(() => _isMuted = !_isMuted);
    }
  }
  
  Future<void> _toggleVideo() async {
    if (_callClient != null) {
      await _callClient!.setLocalVideo(!_isVideoOff);
      setState(() => _isVideoOff = !_isVideoOff);
    }
  }
  
  Future<void> _endVideoCall() async {
    if (_callClient != null) {
      await _callClient!.leave();
      _callClient = null;
    }
  }
  
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: _isVideoInitialized && _callClient != null
        ? VideoView(callClient: _callClient!) // Or use custom video rendering
        : Center(child: CircularProgressIndicator()),
    );
  }
}
```

## Next Steps

1. Run `flutter pub get` in both apps
2. Check for compilation errors
3. Update the code to use the new `CallClient` API
4. Test video calls

## Resources

- Official Demo: https://github.com/daily-demos/daily-flutter-demo
- API Docs: https://pub.dev/documentation/daily_flutter/latest/
- Daily.co Docs: https://docs.daily.co/reference/flutter
