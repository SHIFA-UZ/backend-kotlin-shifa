# Video Call Implementation Example for Existing Screen

## Overview

Your app already has a `VideoCallScreen` at `lib/features/appointments/presentation/video_call_screen.dart`. This document shows how to integrate Daily.co into the existing UI structure.

## Quick Integration Steps

### 1. Add Daily.co SDK to pubspec.yaml

```yaml
dependencies:
  daily_flutter: ^0.1.0  # Check latest version at pub.dev
```

### 2. Create Video Call Service

**File: `lib/core/services/daily_video_service.dart`**

```dart
import 'package:daily_flutter/daily_flutter.dart';
import 'package:dio/dio.dart';

class DailyVideoService {
  final Dio _dio;
  final String _baseUrl;
  
  DailyVideoService({required String baseUrl}) 
    : _baseUrl = baseUrl,
      _dio = Dio(BaseOptions(baseUrl: baseUrl));
  
  Future<Map<String, dynamic>> getVideoToken({
    required String roomName,
    required String userId,
    required String userName,
    bool isOwner = false,
  }) async {
    try {
      final response = await _dio.post(
        '/api/video/token',
        data: {
          'roomName': roomName,
          'userId': userId,
          'userName': userName,
          'isOwner': isOwner,
        },
      );
      return response.data;
    } catch (e) {
      throw Exception('Failed to get video token: $e');
    }
  }
}
```

### 3. Update Existing VideoCallScreen

Replace the placeholder video canvas (lines 496-532) with actual Daily.co integration:

**In `_VideoCallScreenState` class, add:**

```dart
class _VideoCallScreenState extends ConsumerState<VideoCallScreen> {
  // ... existing fields ...
  
  // Add these new fields
  DailyCallFrame? _callFrame;
  DailyVideoService? _videoService;
  bool _isVideoInitialized = false;
  bool _isMuted = false;
  bool _isVideoOff = false;
  bool _isScreenSharing = false;
  
  @override
  void initState() {
    super.initState();
    // ... existing initState code ...
    _initializeVideoCall(); // Add this
  }
  
  Future<void> _initializeVideoCall() async {
    try {
      // Get your API base URL from config
      final api = ref.read(apiClientProvider);
      final baseUrl = api.options.baseUrl;
      
      _videoService = DailyVideoService(baseUrl: baseUrl);
      
      // Generate room name from appointment ID
      final roomName = 'appointment-${widget.appointment.id}';
      
      // Get current user info (adjust based on your auth system)
      final userId = 'doctor-${widget.appointment.doctorId}'; // or from auth state
      final userName = widget.appointment.patientName; // or doctor name
      
      // Get token from backend
      final tokenData = await _videoService!.getVideoToken(
        roomName: roomName,
        userId: userId,
        userName: userName,
        isOwner: true, // Doctor is owner
      );
      
      // Create call frame
      _callFrame = await DailyCallFrame.create(
        roomUrl: tokenData['roomUrl'],
        token: tokenData['token'],
        options: DailyCallFrameOptions(
          showLeaveButton: false, // We'll use our own button
          showFullscreenButton: true,
          showLocalVideo: true,
          showParticipantsBar: true,
        ),
      );
      
      // Set up event listeners
      _callFrame!.onEvent((event) {
        _handleCallEvent(event);
      });
      
      // Join the call
      await _callFrame!.join();
      
      setState(() => _isVideoInitialized = true);
    } catch (e) {
      debugPrint('Failed to initialize video call: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Failed to start video call: $e'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }
  
  void _handleCallEvent(DailyEvent event) {
    switch (event.type) {
      case DailyEventType.participantJoined:
        debugPrint('Participant joined: ${event.participant?.userId}');
        break;
      case DailyEventType.participantLeft:
        debugPrint('Participant left: ${event.participant?.userId}');
        break;
      case DailyEventType.error:
        debugPrint('Call error: ${event.error}');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('Call error: ${event.error?.message ?? "Unknown error"}'),
              backgroundColor: Colors.red,
            ),
          );
        }
        break;
      case DailyEventType.left:
        // Call ended, navigate back
        if (mounted) {
          Navigator.pop(context);
        }
        break;
      default:
        break;
    }
  }
  
  Future<void> _toggleMute() async {
    if (_callFrame != null) {
      try {
        await _callFrame!.setLocalAudio(!_isMuted);
        setState(() => _isMuted = !_isMuted);
      } catch (e) {
        debugPrint('Failed to toggle mute: $e');
      }
    }
  }
  
  Future<void> _toggleVideo() async {
    if (_callFrame != null) {
      try {
        await _callFrame!.setLocalVideo(!_isVideoOff);
        setState(() => _isVideoOff = !_isVideoOff);
      } catch (e) {
        debugPrint('Failed to toggle video: $e');
      }
    }
  }
  
  Future<void> _toggleScreenShare() async {
    if (_callFrame != null) {
      try {
        if (_isScreenSharing) {
          await _callFrame!.stopScreenShare();
        } else {
          await _callFrame!.startScreenShare();
        }
        setState(() => _isScreenSharing = !_isScreenSharing);
      } catch (e) {
        debugPrint('Failed to toggle screen share: $e');
      }
    }
  }
  
  Future<void> _endVideoCall() async {
    if (_callFrame != null) {
      try {
        await _callFrame!.leave();
        await _callFrame!.destroy();
        _callFrame = null;
      } catch (e) {
        debugPrint('Error ending call: $e');
      }
    }
  }
  
  @override
  void dispose() {
    _endVideoCall();
    _notesController.dispose();
    super.dispose();
  }
  
  // ... rest of existing code ...
}
```

### 4. Replace Video Canvas Placeholder

**Replace the video canvas section (around line 496-532) with:**

```dart
// Video canvas with Daily.co
Expanded(
  child: Container(
    decoration: BoxDecoration(
      color: Colors.black,
      borderRadius: BorderRadius.circular(12),
    ),
    child: ClipRRect(
      borderRadius: BorderRadius.circular(12),
      child: _isVideoInitialized && _callFrame != null
        ? DailyCallFrameWidget(callFrame: _callFrame!)
        : Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                CircularProgressIndicator(),
                SizedBox(height: 16),
                Text(
                  'Connecting to video call...',
                  style: TextStyle(color: Colors.white),
                ),
              ],
            ),
          ),
    ),
  ),
),
```

### 5. Update Call Controls

**Update `_CallControls` widget to be functional:**

```dart
class _CallControls extends ConsumerStatefulWidget {
  final VoidCallback? onMute;
  final VoidCallback? onVideo;
  final VoidCallback? onScreenShare;
  final VoidCallback? onEndCall;
  final bool isMuted;
  final bool isVideoOff;
  final bool isScreenSharing;
  
  const _CallControls({
    this.onMute,
    this.onVideo,
    this.onScreenShare,
    this.onEndCall,
    this.isMuted = false,
    this.isVideoOff = false,
    this.isScreenSharing = false,
  });
  
  @override
  ConsumerState<_CallControls> createState() => _CallControlsState();
}

class _CallControlsState extends ConsumerState<_CallControls> {
  @override
  Widget build(BuildContext context) {
    final bg = Colors.black.withOpacity(0.35);
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(24),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _RoundBtn(
            icon: widget.isVideoOff ? Icons.videocam_off : Icons.videocam,
            onPressed: widget.onVideo,
            color: widget.isVideoOff ? Colors.red : Colors.white,
          ),
          const SizedBox(width: 8),
          _RoundBtn(
            icon: widget.isMuted ? Icons.mic_off : Icons.mic,
            onPressed: widget.onMute,
            color: widget.isMuted ? Colors.red : Colors.white,
          ),
          const SizedBox(width: 8),
          _RoundBtn(
            icon: Icons.screen_share,
            onPressed: widget.onScreenShare,
            color: widget.isScreenSharing ? Colors.green : Colors.white,
          ),
          const SizedBox(width: 8),
          _RoundBtn(
            icon: Icons.more_horiz,
            onPressed: () {
              // Show more options menu
            },
          ),
          const SizedBox(width: 8),
          _RoundBtn(
            icon: Icons.call_end,
            color: const Color(0xFFE75656),
            onPressed: widget.onEndCall,
          ),
        ],
      ),
    );
  }
}

class _RoundBtn extends StatelessWidget {
  const _RoundBtn({
    required this.icon,
    this.color,
    this.onPressed,
  });
  
  final IconData icon;
  final Color? color;
  final VoidCallback? onPressed;
  
  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onPressed,
      child: CircleAvatar(
        radius: 22,
        backgroundColor: Colors.white,
        child: Icon(icon, color: color ?? Colors.black87),
      ),
    );
  }
}
```

**Then in the build method, pass the callbacks:**

```dart
Align(
  alignment: Alignment.bottomCenter,
  child: _CallControls(
    onMute: _toggleMute,
    onVideo: _toggleVideo,
    onScreenShare: _toggleScreenShare,
    onEndCall: _endVideoCall,
    isMuted: _isMuted,
    isVideoOff: _isVideoOff,
    isScreenSharing: _isScreenSharing,
  ),
),
```

### 6. Backend Endpoint

**Add to your Spring Boot backend (`VideoController.kt`):**

```kotlin
@RestController
@RequestMapping("/api/video")
class VideoController {
    
    @Value("\${daily.api.key}")
    private lateinit var dailyApiKey: String
    
    @PostMapping("/token")
    fun generateToken(@RequestBody request: VideoTokenRequest): ResponseEntity<VideoTokenResponse> {
        // Validate user has access to this appointment
        // ... your validation logic ...
        
        // Generate Daily.co token using their REST API
        val client = HttpClient.newHttpClient()
        val requestBody = """
        {
            "properties": {
                "room_name": "${request.roomName}",
                "user_id": "${request.userId}",
                "user_name": "${request.userName}",
                "is_owner": ${request.isOwner},
                "exp": ${System.currentTimeMillis() / 1000 + 7200}
            }
        }
        """.trimIndent()
        
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://api.daily.co/v1/meeting-tokens"))
            .header("Authorization", "Bearer $dailyApiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
        
        val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() == 200) {
            val jsonResponse = JSONObject(response.body())
            val token = jsonResponse.getString("token")
            
            // Get or create room
            val roomUrl = getOrCreateRoom(request.roomName)
            
            return ResponseEntity.ok(VideoTokenResponse(
                token = token,
                roomUrl = roomUrl,
                roomName = request.roomName
            ))
        } else {
            throw RuntimeException("Failed to generate token: ${response.body()}")
        }
    }
    
    private fun getOrCreateRoom(roomName: String): String {
        // Check if room exists, create if not
        // Implementation similar to token generation
        // Returns room URL
    }
}

data class VideoTokenRequest(
    val roomName: String,
    val userId: String,
    val userName: String,
    val isOwner: Boolean = false
)

data class VideoTokenResponse(
    val token: String,
    val roomUrl: String,
    val roomName: String
)
```

**Add to `application.properties`:**
```properties
daily.api.key=your_daily_api_key_here
```

### 7. Patient App Integration

For the patient app (`shifa_patient_app_v1`), use the same approach but with `isOwner: false`:

```dart
final tokenData = await _videoService!.getVideoToken(
  roomName: 'appointment-${appointmentId}',
  userId: 'patient-${patientId}',
  userName: patientName,
  isOwner: false, // Patient is not owner
);
```

## Testing

1. **Test with two devices:**
   - Doctor app on one device
   - Patient app on another device
   - Both join the same room name

2. **Test scenarios:**
   - ✅ Both users can see/hear each other
   - ✅ Mute/unmute works
   - ✅ Video on/off works
   - ✅ Screen sharing works (if enabled)
   - ✅ Poor network handling
   - ✅ Call ends properly

## Next Steps

1. Sign up for Daily.co account
2. Get API key
3. Add backend endpoint
4. Update Flutter code as shown above
5. Test with two devices
6. Deploy to production

## Troubleshooting

**Issue: "Failed to get video token"**
- Check API key is correct in backend
- Verify backend endpoint is accessible
- Check CORS settings

**Issue: "Camera/Microphone not working"**
- Verify permissions are granted
- Check device settings
- Test permissions before call

**Issue: "Can't see other participant"**
- Verify both users joined same room
- Check network connection
- Verify tokens are valid
