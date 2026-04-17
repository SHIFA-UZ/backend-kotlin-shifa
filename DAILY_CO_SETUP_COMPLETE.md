# Daily.co Setup Complete ✅

## Configuration Applied

Your Daily.co API key has been configured in the backend:

- **API Key:** `7e5614c8eb388445d63dec33b390e8cc9e396185517deff5f13be5ece7b5e714`
- **Daily.co URL:** `https://shifauz.daily.co/shifauz`
- **API URL:** `https://api.daily.co/v1`

---

## 🚀 Quick Start

### 1. Install Flutter Dependencies

Run these commands to install the Daily.co Flutter package:

```bash
# Doctor App
cd c:\shifa_doc_app_v1
flutter pub get

# Patient App
cd c:\shifa_patient_app_v1
flutter pub get
```

### 2. Start Backend Server

```bash
cd c:\shifa-doctor-backend
./gradlew bootRun
```

The backend will start on `http://localhost:8080` with your Daily.co API key configured.

### 3. Test Video Call

1. **Start Doctor App:**
   - Run the doctor app on one device/emulator
   - Login as a doctor
   - Navigate to an appointment
   - Tap "Start Video Call"

2. **Start Patient App:**
   - Run the patient app on another device/emulator
   - Login as a patient
   - Navigate to the same appointment
   - Tap "Join Video Call"

3. **Both users should:**
   - See each other's video
   - Be able to mute/unmute audio
   - Be able to turn video on/off
   - See real-time connection status

---

## 📱 Testing Checklist

- [ ] Backend server is running
- [ ] Doctor app installed and dependencies updated
- [ ] Patient app installed and dependencies updated
- [ ] Both users logged in
- [ ] Both users have access to the same appointment
- [ ] Camera and microphone permissions granted
- [ ] Video call connects successfully
- [ ] Audio works (can hear each other)
- [ ] Video works (can see each other)
- [ ] Mute/unmute works
- [ ] Video on/off works

---

## 🔧 Troubleshooting

### If video call doesn't start:

1. **Check Backend Logs:**
   - Look for Daily.co API errors
   - Verify API key is being read correctly
   - Check authentication/authorization

2. **Check Flutter Logs:**
   - Look for token generation errors
   - Check network connectivity
   - Verify appointment ID is correct

3. **Check Permissions:**
   - Android: Settings → Apps → Your App → Permissions
   - iOS: Settings → Privacy → Camera/Microphone

4. **Check Network:**
   - Both devices need internet connection
   - Firewall/VPN might block WebRTC connections

### Common Issues:

**"Failed to get video token"**
- ✅ Backend is running
- ✅ User is authenticated
- ✅ User has access to appointment
- ✅ API key is correct

**"Camera/Microphone not working"**
- ✅ Permissions granted
- ✅ Device has camera/microphone
- ✅ No other app using camera/mic

**"Can't see other participant"**
- ✅ Both users joined same room
- ✅ Network connection stable
- ✅ Tokens not expired

---

## 📝 API Endpoints

### Generate Video Token
```
POST /api/video/token
Authorization: Bearer <jwt_token>
Content-Type: application/json

{
  "appointmentId": 123
}

Response:
{
  "token": "eyJhbGc...",
  "roomUrl": "https://shifauz.daily.co/appointment-123",
  "roomName": "appointment-123"
}
```

---

## 🎉 You're Ready!

Everything is configured and ready to use. Just:
1. Install Flutter dependencies
2. Start the backend
3. Test video calls!

---

## 🔒 Security Note

For production, consider:
- Moving API key to environment variable
- Using `DAILY_API_KEY` environment variable instead of hardcoding
- Enabling HIPAA BAA if handling medical data ($500/month)

---

**Need Help?**
- Daily.co Docs: https://docs.daily.co/
- Daily.co Support: support@daily.co
- Check backend logs for detailed error messages
