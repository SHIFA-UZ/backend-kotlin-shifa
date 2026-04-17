# Video Call Integration Guide for Telemedicine App

## Executive Summary

This guide provides a comprehensive analysis of video call APIs/SDKs suitable for telemedicine applications, with detailed comparison, recommendations, and step-by-step integration instructions.

---

## 1. Video Call API/SDK Comparison Table

| Service | Free Tier | Pricing (After Free Tier) | Platforms | Backend Requirements | HIPAA Compliance | Notes |
|---------|-----------|---------------------------|-----------|---------------------|------------------|-------|
| **Daily.co** | 10,000 free minutes/month | $0.0015-$0.004/participant-minute (video)<br>$0.00036-$0.00099/participant-minute (audio) | Web, iOS, Android, Flutter, React Native | Minimal (token generation endpoint) | ✅ HIPAA compliant<br>BAA available ($500/month) | Best overall for telemedicine |
| **Agora.io** | 10,000 free minutes/month | $0.99/1,000 min (audio)<br>$3.99/1,000 min (HD video)<br>$8.99/1,000 min (Full HD) | Web, iOS, Android, Flutter, React Native | Token server required | ⚠️ HIPAA available (contact sales) | High quality, complex pricing |
| **VideoSDK.live** | $20 one-time free balance | $0.001/min (audio)<br>$0.004/min (video) | Web, iOS, Android, Flutter, React Native | Token generation endpoint | ⚠️ HIPAA in Beta (contact sales) | Simple pricing, good docs |
| **ZEGOCLOUD** | Free trial (limited) | $0.99/1,000 min (audio)<br>$3.99/1,000 min (HD)<br>$12.99/1,000 min (Full HD) | Web, iOS, Android, Flutter, React Native | Token server required | ⚠️ HIPAA available (contact sales) | Good Flutter support |
| **Jitsi Meet** | Free (self-hosted) | Self-hosted: Infrastructure costs only<br>JaaS: Contact 8x8 | Web, iOS, Android | Self-hosted: Full server setup<br>JaaS: Minimal | ⚠️ Depends on deployment | Open source, full control |
| **CometChat** | Free tier available | Contact for pricing | Web, iOS, Android, Flutter | Minimal backend | ✅ HIPAA compliant<br>BAA available | Includes chat + video |
| **8x8 Jitsi (JaaS)** | Contact sales | Contact sales | Web, iOS, Android | Minimal | ✅ HIPAA compatible | Enterprise-grade |

### Key Metrics Comparison

| Feature | Daily.co | Agora | VideoSDK | ZEGOCLOUD | Jitsi (Self-hosted) |
|---------|----------|-------|----------|-----------|---------------------|
| **Ease of Integration** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| **Video Quality** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **Cost Efficiency** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **HIPAA Ready** | ✅ Yes | ⚠️ Contact | ⚠️ Beta | ⚠️ Contact | ⚠️ Depends |
| **Backend Complexity** | Low | Medium | Low | Medium | High (self-hosted) |
| **Documentation** | Excellent | Good | Excellent | Good | Good |

---

## 2. Recommended Solution: Daily.co

### Why Daily.co is Best for Your Telemedicine App

**Priority Ranking:**
1. ✅ **Cost** - 10,000 free minutes/month + lowest paid pricing ($0.0015/min)
2. ✅ **Video Quality** - HD quality at no extra charge, excellent performance
3. ✅ **Security** - HIPAA compliant with BAA, SOC-2, GDPR certified
4. ✅ **Ease of Integration** - Minimal backend, excellent Flutter SDK
5. ✅ **Backend Complexity** - Only need token generation endpoint

### Additional Advantages:
- **No credit card required** for free tier
- **Automatic volume discounts** as you scale
- **Excellent Flutter support** with pre-built UI components
- **Built-in features**: Screen sharing, recording, transcription
- **Reliable infrastructure** with 99.99% uptime SLA
- **Active community** and responsive support

### Cost Example:
- **100 video calls/month** (30 min each) = 3,000 minutes = **FREE** (within 10,000 limit)
- **500 video calls/month** (30 min each) = 15,000 minutes = 5,000 paid minutes × $0.0015 = **$7.50/month**

---

## 3. Step-by-Step Integration Guide: Daily.co

### Prerequisites Checklist
- [ ] Daily.co account (sign up at https://daily.co)
- [ ] API key from Daily.co dashboard
- [ ] Backend server (Node.js/Express or serverless)
- [ ] Flutter app with camera/microphone permissions

---

### Step 1: Install Daily.co Flutter SDK

```bash
# In your Flutter project directory
flutter pub add daily_flutter
```

**For Android (`android/app/build.gradle`):**
```gradle
android {
    defaultConfig {
        minSdkVersion 24  // Required for Daily.co
    }
}
```

**For iOS (`ios/Podfile`):**
```ruby
platform :ios, '13.0'  # Minimum iOS version
```

**Android Permissions (`android/app/src/main/AndroidManifest.xml`):**
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

**iOS Permissions (`ios/Runner/Info.plist`):**
```xml
<key>NSCameraUsageDescription</key>
<string>We need camera access for video consultations</string>
<key>NSMicrophoneUsageDescription</key>
<string>We need microphone access for video consultations</string>
```

---

### Step 2: Backend Setup - Token Generation

Create a secure endpoint to generate Daily.co room tokens. This is required for authentication.

#### Node.js/Express Example

**Install dependencies:**
```bash
npm install express cors dotenv @daily-co/daily-js
```

**Server code (`server/routes/video.js`):**
```javascript
const express = require('express');
const router = express.Router();
const Daily = require('@daily-co/daily-js');

const dailyClient = Daily.createClient({
  apiKey: process.env.DAILY_API_KEY,
  baseUrl: process.env.DAILY_API_URL || 'https://api.daily.co/v1'
});

// Generate token for joining a room
router.post('/video/token', async (req, res) => {
  try {
    const { roomName, userId, userName, isOwner } = req.body;
    
    // Validate user is authenticated (add your auth middleware)
    // const user = await authenticateUser(req);
    
    // Create or get room
    let room;
    try {
      room = await dailyClient.rooms.get({ name: roomName });
    } catch (error) {
      // Room doesn't exist, create it
      room = await dailyClient.rooms.create({
        name: roomName,
        privacy: 'private',
        properties: {
          exp: Math.floor(Date.now() / 1000) + (60 * 60 * 24), // 24 hours
          enable_chat: true,
          enable_screenshare: true,
          enable_recording: false, // Enable if needed
        }
      });
    }
    
    // Generate token
    const token = await dailyClient.meetingTokens.create({
      properties: {
        room_name: roomName,
        user_id: userId,
        user_name: userName,
        is_owner: isOwner || false,
        exp: Math.floor(Date.now() / 1000) + (60 * 60 * 2), // 2 hours
      }
    });
    
    res.json({
      token: token.token,
      roomUrl: room.url,
      roomName: roomName
    });
  } catch (error) {
    console.error('Token generation error:', error);
    res.status(500).json({ error: 'Failed to generate video token' });
  }
});

// Create a room (optional - can be done on-demand)
router.post('/video/room', async (req, res) => {
  try {
    const { roomName, maxParticipants = 2 } = req.body;
    
    const room = await dailyClient.rooms.create({
      name: roomName,
      privacy: 'private',
      properties: {
        max_participants: maxParticipants,
        enable_chat: true,
        enable_screenshare: true,
        exp: Math.floor(Date.now() / 1000) + (60 * 60 * 24), // 24 hours
      }
    });
    
    res.json({
      roomName: room.name,
      roomUrl: room.url,
      config: room.config
    });
  } catch (error) {
    console.error('Room creation error:', error);
    res.status(500).json({ error: 'Failed to create room' });
  }
});

module.exports = router;
```

**Environment variables (`.env`):**
```env
DAILY_API_KEY=your_daily_api_key_here
DAILY_API_URL=https://api.daily.co/v1
```

**Server setup (`server/index.js`):**
```javascript
require('dotenv').config();
const express = require('express');
const cors = require('cors');
const videoRoutes = require('./routes/video');

const app = express();
app.use(cors());
app.use(express.json());

app.use('/api', videoRoutes);

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`Server running on port ${PORT}`);
});
```

#### Serverless Function Example (AWS Lambda / Vercel)

**Vercel Function (`api/video/token.js`):**
```javascript
const Daily = require('@daily-co/daily-js');

export default async function handler(req, res) {
  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }
  
  const dailyClient = Daily.createClient({
    apiKey: process.env.DAILY_API_KEY,
  });
  
  try {
    const { roomName, userId, userName } = req.body;
    
    const token = await dailyClient.meetingTokens.create({
      properties: {
        room_name: roomName,
        user_id: userId,
        user_name: userName,
        exp: Math.floor(Date.now() / 1000) + (60 * 60 * 2),
      }
    });
    
    res.json({ token: token.token });
  } catch (error) {
    res.status(500).json({ error: 'Failed to generate token' });
  }
}
```

---

### Step 3: Flutter Integration

#### Create Video Call Service

**File: `lib/core/services/video_call_service.dart`**
```dart
import 'package:daily_flutter/daily_flutter.dart';
import 'package:dio/dio.dart';

class VideoCallService {
  final Dio _dio;
  final String _baseUrl;
  
  VideoCallService({required String baseUrl}) 
    : _baseUrl = baseUrl,
      _dio = Dio(BaseOptions(baseUrl: baseUrl));
  
  // Get token from backend
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
  
  // Create Daily.co call frame
  Future<DailyCallFrame> createCallFrame({
    required String roomUrl,
    required String token,
  }) async {
    final callFrame = await DailyCallFrame.create(
      roomUrl: roomUrl,
      token: token,
      options: DailyCallFrameOptions(
        showLeaveButton: true,
        showFullscreenButton: true,
        showLocalVideo: true,
        showParticipantsBar: true,
        iframeStyle: {
          'position': 'fixed',
          'width': '100%',
          'height': '100%',
          'border': 'none',
        },
      ),
    );
    return callFrame;
  }
}
```

#### Create Video Call Screen Widget

**File: `lib/features/video_call/presentation/video_call_screen.dart`**
```dart
import 'package:flutter/material.dart';
import 'package:daily_flutter/daily_flutter.dart';
import 'package:shifa_doc_app_v1/core/services/video_call_service.dart';

class VideoCallScreen extends StatefulWidget {
  final String roomName;
  final String userId;
  final String userName;
  final bool isOwner;
  
  const VideoCallScreen({
    Key? key,
    required this.roomName,
    required this.userId,
    required this.userName,
    required this.isOwner,
  }) : super(key: key);
  
  @override
  State<VideoCallScreen> createState() => _VideoCallScreenState();
}

class _VideoCallScreenState extends State<VideoCallScreen> {
  DailyCallFrame? _callFrame;
  VideoCallService? _videoService;
  bool _isLoading = true;
  bool _isMuted = false;
  bool _isVideoOff = false;
  String? _error;
  
  @override
  void initState() {
    super.initState();
    _initializeCall();
  }
  
  Future<void> _initializeCall() async {
    try {
      setState(() => _isLoading = true);
      
      // Initialize service (get baseUrl from your config)
      _videoService = VideoCallService(
        baseUrl: 'https://your-backend-url.com',
      );
      
      // Get token from backend
      final tokenData = await _videoService!.getVideoToken(
        roomName: widget.roomName,
        userId: widget.userId,
        userName: widget.userName,
        isOwner: widget.isOwner,
      );
      
      // Create call frame
      _callFrame = await _videoService!.createCallFrame(
        roomUrl: tokenData['roomUrl'],
        token: tokenData['token'],
      );
      
      // Set up event listeners
      _callFrame!.onEvent((event) {
        _handleCallEvent(event);
      });
      
      // Join the call
      await _callFrame!.join();
      
      setState(() => _isLoading = false);
    } catch (e) {
      setState(() {
        _error = e.toString();
        _isLoading = false;
      });
    }
  }
  
  void _handleCallEvent(DailyEvent event) {
    switch (event.type) {
      case DailyEventType.participantJoined:
        print('Participant joined: ${event.participant?.userId}');
        break;
      case DailyEventType.participantLeft:
        print('Participant left: ${event.participant?.userId}');
        break;
      case DailyEventType.error:
        print('Call error: ${event.error}');
        _showError(event.error?.message ?? 'Unknown error');
        break;
      case DailyEventType.left:
        Navigator.of(context).pop();
        break;
      default:
        break;
    }
  }
  
  Future<void> _toggleMute() async {
    if (_callFrame != null) {
      await _callFrame!.setLocalAudio(!_isMuted);
      setState(() => _isMuted = !_isMuted);
    }
  }
  
  Future<void> _toggleVideo() async {
    if (_callFrame != null) {
      await _callFrame!.setLocalVideo(!_isVideoOff);
      setState(() => _isVideoOff = !_isVideoOff);
    }
  }
  
  Future<void> _leaveCall() async {
    if (_callFrame != null) {
      await _callFrame!.leave();
    }
    Navigator.of(context).pop();
  }
  
  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }
  
  @override
  void dispose() {
    _callFrame?.leave();
    _callFrame?.destroy();
    super.dispose();
  }
  
  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }
    
    if (_error != null) {
      return Scaffold(
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text('Error: $_error'),
              ElevatedButton(
                onPressed: () => Navigator.of(context).pop(),
                child: Text('Go Back'),
              ),
            ],
          ),
        ),
      );
    }
    
    return Scaffold(
      body: Stack(
        children: [
          // Daily.co call frame (web view)
          if (_callFrame != null)
            DailyCallFrameWidget(callFrame: _callFrame!),
          
          // Control buttons overlay
          Positioned(
            bottom: 40,
            left: 0,
            right: 0,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // Mute/Unmute button
                FloatingActionButton(
                  onPressed: _toggleMute,
                  backgroundColor: _isMuted ? Colors.red : Colors.blue,
                  child: Icon(_isMuted ? Icons.mic_off : Icons.mic),
                ),
                SizedBox(width: 20),
                
                // Video on/off button
                FloatingActionButton(
                  onPressed: _toggleVideo,
                  backgroundColor: _isVideoOff ? Colors.red : Colors.blue,
                  child: Icon(_isVideoOff ? Icons.videocam_off : Icons.videocam),
                ),
                SizedBox(width: 20),
                
                // Leave call button
                FloatingActionButton(
                  onPressed: _leaveCall,
                  backgroundColor: Colors.red,
                  child: Icon(Icons.call_end),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
```

#### Alternative: Using Daily.co Pre-built UI (Easier)

**File: `lib/features/video_call/presentation/video_call_screen_simple.dart`**
```dart
import 'package:flutter/material.dart';
import 'package:daily_flutter/daily_flutter.dart';
import 'package:shifa_doc_app_v1/core/services/video_call_service.dart';

class VideoCallScreenSimple extends StatefulWidget {
  final String roomName;
  final String userId;
  final String userName;
  
  const VideoCallScreenSimple({
    Key? key,
    required this.roomName,
    required this.userId,
    required this.userName,
  }) : super(key: key);
  
  @override
  State<VideoCallScreenSimple> createState() => _VideoCallScreenSimpleState();
}

class _VideoCallScreenSimpleState extends State<VideoCallScreenSimple> {
  DailyCallFrame? _callFrame;
  bool _isLoading = true;
  
  @override
  void initState() {
    super.initState();
    _startCall();
  }
  
  Future<void> _startCall() async {
    try {
      final videoService = VideoCallService(
        baseUrl: 'https://your-backend-url.com',
      );
      
      final tokenData = await videoService.getVideoToken(
        roomName: widget.roomName,
        userId: widget.userId,
        userName: widget.userName,
      );
      
      _callFrame = await DailyCallFrame.create(
        roomUrl: tokenData['roomUrl'],
        token: tokenData['token'],
        options: DailyCallFrameOptions(
          showLeaveButton: true,
          showFullscreenButton: true,
          showLocalVideo: true,
          showParticipantsBar: true,
        ),
      );
      
      _callFrame!.onEvent((event) {
        if (event.type == DailyEventType.left) {
          Navigator.of(context).pop();
        }
      });
      
      await _callFrame!.join();
      setState(() => _isLoading = false);
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Failed to start call: $e')),
      );
      Navigator.of(context).pop();
    }
  }
  
  @override
  void dispose() {
    _callFrame?.leave();
    _callFrame?.destroy();
    super.dispose();
  }
  
  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }
    
    return Scaffold(
      body: _callFrame != null
        ? DailyCallFrameWidget(callFrame: _callFrame!)
        : Center(child: Text('Call ended')),
    );
  }
}
```

#### Navigation to Video Call

**Example usage in your appointment screen:**
```dart
// When user taps "Start Video Call" button
Navigator.push(
  context,
  MaterialPageRoute(
    builder: (context) => VideoCallScreen(
      roomName: 'appointment-${appointmentId}',
      userId: currentUser.id,
      userName: currentUser.name,
      isOwner: currentUser.isDoctor,
    ),
  ),
);
```

---

### Step 4: Web Integration (React Example)

If you have a web version of your app:

**Install:**
```bash
npm install @daily-co/react-native-daily-js
# or for React web
npm install @daily-co/daily-js
```

**React Component:**
```jsx
import React, { useEffect, useRef, useState } from 'react';
import DailyIframe from '@daily-co/daily-js';

function VideoCallScreen({ roomName, userId, userName }) {
  const iframeRef = useRef(null);
  const [callFrame, setCallFrame] = useState(null);
  const [isMuted, setIsMuted] = useState(false);
  const [isVideoOff, setIsVideoOff] = useState(false);
  
  useEffect(() => {
    const initCall = async () => {
      try {
        // Get token from your backend
        const response = await fetch('/api/video/token', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ roomName, userId, userName }),
        });
        const { token, roomUrl } = await response.json();
        
        // Create call frame
        const frame = DailyIframe.createFrame(iframeRef.current, {
          showLeaveButton: true,
          showFullscreenButton: true,
          showLocalVideo: true,
          iframeStyle: {
            position: 'fixed',
            width: '100%',
            height: '100%',
            border: 'none',
          },
        });
        
        // Join call
        await frame.join({ url: roomUrl, token });
        
        // Event listeners
        frame.on('left-meeting', () => {
          window.location.href = '/appointments';
        });
        
        frame.on('error', (e) => {
          console.error('Call error:', e);
        });
        
        setCallFrame(frame);
      } catch (error) {
        console.error('Failed to start call:', error);
      }
    };
    
    initCall();
    
    return () => {
      if (callFrame) {
        callFrame.leave();
        callFrame.destroy();
      }
    };
  }, []);
  
  const toggleMute = async () => {
    if (callFrame) {
      await callFrame.setLocalAudio(!isMuted);
      setIsMuted(!isMuted);
    }
  };
  
  const toggleVideo = async () => {
    if (callFrame) {
      await callFrame.setLocalVideo(!isVideoOff);
      setIsVideoOff(!isVideoOff);
    }
  };
  
  const leaveCall = async () => {
    if (callFrame) {
      await callFrame.leave();
    }
  };
  
  return (
    <div style={{ position: 'relative', width: '100%', height: '100vh' }}>
      <div ref={iframeRef} style={{ width: '100%', height: '100%' }} />
      
      {/* Control buttons */}
      <div style={{
        position: 'absolute',
        bottom: '40px',
        left: '50%',
        transform: 'translateX(-50%)',
        display: 'flex',
        gap: '20px',
      }}>
        <button onClick={toggleMute} style={{
          width: '60px',
          height: '60px',
          borderRadius: '50%',
          border: 'none',
          backgroundColor: isMuted ? '#f44336' : '#2196F3',
          color: 'white',
          cursor: 'pointer',
        }}>
          {isMuted ? '🔇' : '🎤'}
        </button>
        
        <button onClick={toggleVideo} style={{
          width: '60px',
          height: '60px',
          borderRadius: '50%',
          border: 'none',
          backgroundColor: isVideoOff ? '#f44336' : '#2196F3',
          color: 'white',
          cursor: 'pointer',
        }}>
          {isVideoOff ? '📹❌' : '📹'}
        </button>
        
        <button onClick={leaveCall} style={{
          width: '60px',
          height: '60px',
          borderRadius: '50%',
          border: 'none',
          backgroundColor: '#f44336',
          color: 'white',
          cursor: 'pointer',
        }}>
          📞❌
        </button>
      </div>
    </div>
  );
}

export default VideoCallScreen;
```

---

## 4. Best Practices for Telemedicine Video Calls

### 4.1 Handling Poor Network Conditions

```dart
// Monitor network quality
_callFrame?.onEvent((event) {
  if (event.type == DailyEventType.networkQualityChanged) {
    final quality = event.networkQuality;
    if (quality == NetworkQualityLevel.poor) {
      // Show warning to user
      _showNetworkWarning();
      // Optionally reduce video quality
      _callFrame?.setVideoQuality('low');
    }
  }
});

void _showNetworkWarning() {
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text('Poor network connection detected'),
      duration: Duration(seconds: 5),
    ),
  );
}
```

**Best Practices:**
- **Auto-adjust quality**: Reduce video resolution when network is poor
- **Show network indicator**: Display connection quality to users
- **Graceful degradation**: Switch to audio-only if video fails
- **Retry logic**: Implement automatic reconnection on disconnect

### 4.2 Audio/Video Controls

```dart
// Always provide clear UI controls
Future<void> toggleAudio() async {
  try {
    await _callFrame?.setLocalAudio(!_isMuted);
    setState(() => _isMuted = !_isMuted);
    
    // Show feedback
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(_isMuted ? 'Microphone muted' : 'Microphone unmuted'),
        duration: Duration(seconds: 1),
      ),
    );
  } catch (e) {
    _showError('Failed to toggle audio');
  }
}
```

**Best Practices:**
- **Pre-call permissions**: Request camera/mic permissions before call
- **Visual feedback**: Show mute/video-off indicators clearly
- **Test before call**: Allow users to test audio/video before joining
- **Emergency unmute**: Provide quick way to unmute if needed

### 4.3 Recording Considerations

**HIPAA Compliance:**
- ✅ **Get explicit consent** before recording
- ✅ **Store recordings securely** (encrypted)
- ✅ **Limit access** to authorized personnel only
- ✅ **Maintain audit logs** of who accessed recordings
- ✅ **Set retention policies** (delete after required period)

**Implementation:**
```dart
// Only enable recording with explicit consent
Future<void> startRecording() async {
  // Show consent dialog first
  final consent = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text('Record Consultation'),
      content: Text('This consultation will be recorded. Do you consent?'),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: Text('No'),
        ),
        TextButton(
          onPressed: () => Navigator.pop(context, true),
          child: Text('Yes'),
        ),
      ],
    ),
  );
  
  if (consent == true) {
    await _callFrame?.startRecording();
    // Log consent in your backend
    await _logRecordingConsent(appointmentId: appointmentId);
  }
}
```

### 4.4 Security and Encryption

**Daily.co Security Features:**
- ✅ **End-to-end encryption** (when enabled)
- ✅ **TLS 1.2+** for all connections
- ✅ **Token-based authentication** (expires after set time)
- ✅ **Private rooms** (not discoverable)
- ✅ **HIPAA BAA** available

**Best Practices:**
```dart
// Use short-lived tokens (2 hours max)
final token = await generateToken(
  expiresIn: Duration(hours: 2),
);

// Validate user before generating token (backend)
if (!await validateUserPermissions(userId, appointmentId)) {
  throw UnauthorizedException();
}

// Use private rooms only
final room = await createRoom(
  privacy: 'private',
  properties: {
    'enable_knocking': false, // Prevent unauthorized joins
  },
);
```

### 4.5 Token Handling and Authentication

**Backend Token Generation (Secure):**
```javascript
// Always generate tokens server-side, never client-side
router.post('/video/token', authenticateUser, async (req, res) => {
  const { userId, appointmentId } = req.body;
  
  // Verify user has access to this appointment
  const appointment = await Appointment.findById(appointmentId);
  if (!appointment || 
      (appointment.doctorId !== userId && appointment.patientId !== userId)) {
    return res.status(403).json({ error: 'Unauthorized' });
  }
  
  // Generate short-lived token
  const token = await dailyClient.meetingTokens.create({
    properties: {
      room_name: `appointment-${appointmentId}`,
      user_id: userId,
      exp: Math.floor(Date.now() / 1000) + (60 * 60 * 2), // 2 hours
    }
  });
  
  res.json({ token: token.token });
});
```

**Token Refresh (if needed):**
```dart
// Refresh token before expiration
Timer.periodic(Duration(minutes: 90), (timer) async {
  if (_callFrame != null) {
    final newToken = await _videoService.getVideoToken(
      roomName: widget.roomName,
      userId: widget.userId,
      userName: widget.userName,
    );
    await _callFrame?.updateToken(newToken['token']);
  }
});
```

### 4.6 Error Handling

```dart
void _handleCallEvent(DailyEvent event) {
  switch (event.type) {
    case DailyEventType.error:
      final error = event.error;
      if (error?.type == 'network-error') {
        _handleNetworkError();
      } else if (error?.type == 'permission-denied') {
        _requestPermissions();
      } else {
        _showError(error?.message ?? 'Unknown error');
      }
      break;
      
    case DailyEventType.participantLeft:
      if (event.participant?.userId != widget.userId) {
        _showMessage('Other participant left the call');
      }
      break;
      
    case DailyEventType.left:
      Navigator.of(context).pop();
      break;
  }
}
```

### 4.7 Pre-Call Testing

```dart
class PreCallTestScreen extends StatefulWidget {
  @override
  _PreCallTestScreenState createState() => _PreCallTestScreenState();
}

class _PreCallTestScreenState extends State<PreCallTestScreen> {
  bool _audioWorking = false;
  bool _videoWorking = false;
  
  Future<void> _testAudioVideo() async {
    // Test microphone
    try {
      final stream = await navigator.mediaDevices.getUserMedia(
        {'audio': true, 'video': true},
      );
      setState(() {
        _audioWorking = true;
        _videoWorking = true;
      });
      stream.getTracks().forEach((track) => track.stop());
    } catch (e) {
      _showError('Failed to access camera/microphone');
    }
  }
  
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('Pre-Call Test')),
      body: Column(
        children: [
          Text('Audio: ${_audioWorking ? "✓" : "✗"}'),
          Text('Video: ${_videoWorking ? "✓" : "✗"}'),
          ElevatedButton(
            onPressed: _testAudioVideo,
            child: Text('Test Audio/Video'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text('Continue to Call'),
          ),
        ],
      ),
    );
  }
}
```

---

## 5. Official Documentation and Resources

### Daily.co Resources

**Official Documentation:**
- **Main Docs**: https://docs.daily.co/
- **Flutter SDK**: https://docs.daily.co/reference/daily-flutter
- **REST API**: https://docs.daily.co/reference/rest-api
- **Web SDK**: https://docs.daily.co/reference/daily-js

**Tutorials and Guides:**
- **Getting Started**: https://docs.daily.co/guides/getting-started
- **Flutter Quickstart**: https://docs.daily.co/guides/products/mobile/flutter
- **Token Authentication**: https://docs.daily.co/guides/room-access-tokens
- **HIPAA Compliance**: https://docs.daily.co/guides/hipaa-compliance

**Sample Code:**
- **Flutter Example**: https://github.com/daily-co/daily-flutter-example
- **React Example**: https://github.com/daily-co/daily-react-example
- **Node.js Backend**: https://github.com/daily-co/daily-server-example

**Community:**
- **Discord**: https://discord.gg/daily
- **Support**: support@daily.co

### Alternative Resources (If Needed)

**Agora.io:**
- Docs: https://docs.agora.io/
- Flutter SDK: https://docs.agora.io/en/video-calling/get-started/get-started-sdk?platform=flutter

**VideoSDK.live:**
- Docs: https://docs.videosdk.live/
- Flutter SDK: https://docs.videosdk.live/flutter/guide/video-and-audio-calling-api-sdk/flutter-sdk

**ZEGOCLOUD:**
- Docs: https://docs.zegocloud.com/
- Flutter SDK: https://docs.zegocloud.com/article/3559

---

## 6. Pre-Integration Checklist

Before starting integration, ensure you have:

### Backend Requirements
- [ ] **Daily.co account** created (https://daily.co)
- [ ] **API key** obtained from Daily.co dashboard
- [ ] **Backend endpoint** for token generation (`/api/video/token`)
- [ ] **Authentication middleware** to verify users
- [ ] **Appointment validation** logic (verify user has access)
- [ ] **Environment variables** configured (`.env` file)
- [ ] **CORS configured** (if web app)
- [ ] **Error handling** and logging set up

### Frontend Requirements (Flutter)
- [ ] **Daily.co Flutter SDK** added to `pubspec.yaml`
- [ ] **Android permissions** configured (camera, microphone, internet)
- [ ] **iOS permissions** configured (camera, microphone in Info.plist)
- [ ] **Minimum SDK versions** set (Android 24+, iOS 13+)
- [ ] **Network state handling** implemented
- [ ] **Error UI** components ready
- [ ] **Loading states** implemented

### Security & Compliance
- [ ] **HIPAA BAA** signed with Daily.co (if required)
- [ ] **Token expiration** set (2 hours recommended)
- [ ] **Private rooms** configured (not public)
- [ ] **User authentication** verified before token generation
- [ ] **Recording consent** flow implemented (if recording)
- [ ] **Audit logging** set up for video calls
- [ ] **Data encryption** verified

### Testing Requirements
- [ ] **Test account** created on Daily.co
- [ ] **Test devices** ready (iOS, Android, Web)
- [ ] **Network conditions** tested (good/poor WiFi, cellular)
- [ ] **Permission flows** tested
- [ ] **Error scenarios** tested (network loss, permissions denied)
- [ ] **Multi-user testing** (doctor + patient)

### UI/UX Requirements
- [ ] **Video call screen** designed
- [ ] **Control buttons** (mute, video, end call)
- [ ] **Loading states** designed
- [ ] **Error messages** prepared
- [ ] **Network quality indicator** (optional)
- [ ] **Pre-call test screen** (optional but recommended)

---

## 7. Quick Start Summary

1. **Sign up** for Daily.co account → Get API key
2. **Install SDK**: `flutter pub add daily_flutter`
3. **Configure permissions** (Android/iOS)
4. **Create backend endpoint** for token generation
5. **Implement video call screen** in Flutter
6. **Test** with two devices
7. **Deploy** and monitor

**Estimated Integration Time:** 4-8 hours for basic implementation

---

## 8. Support and Troubleshooting

### Common Issues

**Issue: "Failed to get video token"**
- Check API key is correct
- Verify backend endpoint is accessible
- Check CORS settings (for web)

**Issue: "Camera/Microphone not working"**
- Verify permissions are granted
- Check device settings
- Test with pre-call screen

**Issue: "Poor video quality"**
- Check network connection
- Reduce video quality programmatically
- Consider audio-only fallback

**Issue: "Token expired"**
- Implement token refresh logic
- Increase token expiration time (max 24 hours)

### Getting Help

- **Daily.co Support**: support@daily.co
- **Documentation**: https://docs.daily.co
- **Community Discord**: https://discord.gg/daily
- **Stack Overflow**: Tag `daily-co`

---

## Conclusion

Daily.co is the recommended solution for your telemedicine app due to its:
- ✅ **Low cost** (10,000 free minutes/month)
- ✅ **HIPAA compliance** with BAA
- ✅ **Easy integration** with Flutter
- ✅ **Minimal backend** requirements
- ✅ **Excellent documentation** and support

Start with the free tier to test, then scale as needed. The integration is straightforward and can be completed in a day.

**Next Steps:**
1. Create Daily.co account
2. Set up backend token endpoint
3. Integrate Flutter SDK
4. Test with two devices
5. Deploy to production

Good luck with your telemedicine video call implementation! 🎥👨‍⚕️
