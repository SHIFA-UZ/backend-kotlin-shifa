package com.shifa.service

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.shifa.domain.Notification
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

@Service
class FcmService {

    private val log = LoggerFactory.getLogger(FcmService::class.java)

    private var firebaseMessaging: FirebaseMessaging? = null
        get() {
            if (field == null) {
                try {
                    if (FirebaseApp.getApps().isEmpty()) {
                        val json = System.getenv("FIREBASE_SERVICE_ACCOUNT_JSON")?.trim()
                        if (!json.isNullOrBlank()) {
                            val stream = ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8))
                            val credentials = GoogleCredentials.fromStream(stream)
                            val options = FirebaseOptions.builder().setCredentials(credentials).build()
                            FirebaseApp.initializeApp(options)
                            log.info("Firebase Messaging initialized from FIREBASE_SERVICE_ACCOUNT_JSON")
                        } else {
                            FirebaseApp.initializeApp()
                            log.info("Firebase Messaging initialized from default credentials (GOOGLE_APPLICATION_CREDENTIALS)")
                        }
                    }
                    field = FirebaseMessaging.getInstance()
                } catch (e: Exception) {
                    log.warn("Firebase Messaging not configured: {}", e.message)
                }
            }
            return field
        }

    /**
     * Send push notification to the given FCM token.
     * Data map values must be strings (FCM requirement). Safe to call if Firebase is not configured.
     */
    /**
     * @param androidChannelId When set, applies [AndroidConfig] with high priority so notifications
     * are delivered promptly and appear on the correct channel (must match the Flutter app).
     */
    fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
        androidChannelId: String? = null
    ): Boolean {
        if (token.isBlank()) return false
        val messaging = firebaseMessaging ?: run {
            log.warn("Skipping FCM send: FirebaseMessaging not configured")
            return false
        }
        return try {
            val builder = Message.builder()
                .setToken(token)
                .setNotification(
                    com.google.firebase.messaging.Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build()
                )
                .putAllData(data)
            if (!androidChannelId.isNullOrBlank()) {
                builder.setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(
                            AndroidNotification.builder()
                                .setChannelId(androidChannelId)
                                .setSound("default")
                                .build()
                        )
                        .build()
                )
            }
            val message = builder.build()
            messaging.send(message)
            log.info(
                "FCM sent (title={}, tokenPrefix={})",
                title,
                token.take(10)
            )
            true
        } catch (e: Exception) {
            log.warn("FCM send failed: {}", e.message)
            false
        }
    }

    /**
     * Build data map for patient app from a saved notification (matches patient NotificationModel.fromFcmData).
     */
    fun sendPatientNotification(
        fcmToken: String?,
        notification: Notification,
        extraData: Map<String, String> = emptyMap()
    ): Boolean {
        if (fcmToken.isNullOrBlank()) {
            log.info("FCM skip: no token for patient (notification id={})", notification.id)
            return false
        }
        log.info(
            "Sending FCM to patient (notificationId={}, type={}, tokenPrefix={})",
            notification.id,
            notification.type,
            fcmToken.take(10)
        )
        val data = mutableMapOf<String, String>(
            "id" to notification.id.toString(),
            "title" to notification.title,
            "message" to notification.message,
            "type" to notification.type.name,
            "createdAt" to notification.createdAt.toString(),
        )
        notification.appointmentId?.let { data["appointmentId"] = it.toString() }
        notification.patientFormId?.let { data["patientFormId"] = it.toString() }
        notification.documentAccessRequestId?.let { data["documentAccessRequestId"] = it.toString() }
        notification.taskId?.let { data["taskId"] = it.toString() }
        notification.documentId?.let { data["documentId"] = it.toString() }
        notification.documentPatientId?.let { data["patientId"] = it.toString() }
        notification.documentTitle?.let { data["documentTitle"] = it }
        data.putAll(extraData)
        return sendToToken(
            token = fcmToken,
            title = notification.title,
            body = notification.message,
            data = data,
            androidChannelId = "shifa_patient_channel",
        )
    }

    /**
     * Send FCM notification for doctor app. Payload format mirrors patient payload so
     * both apps can parse id/type/appointmentId/taskId/documentAccessRequestId consistently.
     */
    fun sendDoctorNotification(
        fcmToken: String?,
        notification: Notification,
        extraData: Map<String, String> = emptyMap()
    ): Boolean {
        if (fcmToken.isNullOrBlank()) {
            log.info(
                "FCM skip: no token for doctor (doctorId={}, notificationId={})",
                notification.doctor?.id,
                notification.id
            )
            return false
        }
        log.info(
            "Sending FCM to doctor (doctorId={}, notificationId={}, type={}, tokenPrefix={})",
            notification.doctor?.id,
            notification.id,
            notification.type,
            fcmToken.take(10)
        )
        val data = mutableMapOf<String, String>(
            "id" to notification.id.toString(),
            "title" to notification.title,
            "message" to notification.message,
            "type" to notification.type.name,
            "createdAt" to notification.createdAt.toString(),
        )
        notification.appointmentId?.let { data["appointmentId"] = it.toString() }
        notification.documentAccessRequestId?.let { data["documentAccessRequestId"] = it.toString() }
        notification.taskId?.let { data["taskId"] = it.toString() }
        notification.documentId?.let { data["documentId"] = it.toString() }
        notification.documentPatientId?.let { data["patientId"] = it.toString() }
        notification.documentTitle?.let { data["documentTitle"] = it }
        data.putAll(extraData)
        return sendToToken(
            token = fcmToken,
            title = notification.title,
            body = notification.message,
            data = data
        )
    }
}
