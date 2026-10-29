package com.shifa.notifications.infrastructure

import com.shifa.notifications.application.DoctorPushI18n
import com.shifa.notifications.application.FcmTokenCleanupService

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.shifa.notifications.domain.Notification
import com.shifa.appointments.infrastructure.AppointmentRepository
import com.shifa.doctors.infrastructure.DoctorSettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

@Service
class FcmService(
    private val appointments: AppointmentRepository,
    private val doctorSettings: DoctorSettingsRepository,
    private val tokenCleanup: FcmTokenCleanupService,
) {

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
            val code = (e as? FirebaseMessagingException)?.messagingErrorCode
            log.warn("FCM send failed: {} (messagingErrorCode={})", e.message, code)
            if (isPermanentTokenFailure(e)) {
                try {
                    tokenCleanup.clearToken(token)
                } catch (cleanup: Exception) {
                    log.warn("Failed to clear invalid FCM token (tokenPrefix={}): {}", token.take(10), cleanup.message)
                }
            }
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
        notification.appointmentId?.let { apptId ->
            appointments.findById(apptId).orElse(null)?.startAt?.let { startAt ->
                data["appointmentStartAt"] = startAt.toString()
            }
        }
        notification.patientFormId?.let { data["patientFormId"] = it.toString() }
        notification.documentAccessRequestId?.let { data["documentAccessRequestId"] = it.toString() }
        notification.taskId?.let { data["taskId"] = it.toString() }
        notification.treatmentPlanId?.let { data["treatmentPlanId"] = it.toString() }
        notification.installmentItemId?.let { data["installmentItemId"] = it.toString() }
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
        notification.appointmentId?.let { apptId ->
            appointments.findById(apptId).orElse(null)?.startAt?.let { startAt ->
                data["appointmentStartAt"] = startAt.toString()
            }
        }
        notification.documentAccessRequestId?.let { data["documentAccessRequestId"] = it.toString() }
        notification.taskId?.let { data["taskId"] = it.toString() }
        notification.documentId?.let { data["documentId"] = it.toString() }
        notification.documentPatientId?.let { data["patientId"] = it.toString() }
        notification.documentTitle?.let { data["documentTitle"] = it }
        data.putAll(extraData)
        val patientName = resolveDoctorPatientName(notification, data)
        if (patientName.isNotBlank()) {
            data.putIfAbsent("patientName", patientName)
        }
        val lang = doctorUiLanguage(notification.doctor?.id)
        val tray = DoctorPushI18n.trayText(notification, lang, patientName)
        return sendToToken(
            token = fcmToken,
            title = tray.title,
            body = tray.body,
            data = data,
            androidChannelId = DOCTOR_ANDROID_CHANNEL_ID
        )
    }

    private fun doctorUiLanguage(doctorId: Long?): String {
        if (doctorId == null || doctorId == 0L) return "en"
        return try {
            DoctorPushI18n.normalizeLang(
                doctorSettings.findByDoctorId(doctorId).orElse(null)?.language
            )
        } catch (e: Exception) {
            log.debug("Doctor UI language lookup failed: {}", e.message)
            "en"
        }
    }

    private fun resolveDoctorPatientName(
        notification: Notification,
        extraData: Map<String, String>,
    ): String {
        extraData["patientName"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        notification.appointmentId?.let { apptId ->
            appointments.findById(apptId).orElse(null)?.patient?.fullName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return DoctorPushI18n.patientNameFrom(notification, extraData)
    }

    /**
     * Same as [sendDoctorNotification] but after the surrounding transaction
     * commits, so a failed FCM send cannot roll back the business write and
     * the in-app notification row is visible if the doctor taps immediately.
     */
    fun sendDoctorNotificationAfterCommit(
        fcmToken: String?,
        notification: Notification,
        extraData: Map<String, String> = emptyMap()
    ) {
        val deliver = {
            try {
                sendDoctorNotification(fcmToken, notification, extraData)
            } catch (e: Exception) {
                log.warn("FCM after-commit send failed: {}", e.message)
            }
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                object : org.springframework.transaction.support.TransactionSynchronization {
                    override fun afterCommit() {
                        deliver()
                    }
                }
            )
        } else {
            deliver()
        }
    }

    /** Patient counterpart of [sendDoctorNotificationAfterCommit]: the push leaves only once the write is committed. */
    fun sendPatientNotificationAfterCommit(
        fcmToken: String?,
        notification: Notification,
        extraData: Map<String, String> = emptyMap()
    ) {
        val deliver = {
            try {
                sendPatientNotification(fcmToken, notification, extraData)
            } catch (e: Exception) {
                log.warn("FCM after-commit send failed: {}", e.message)
            }
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                object : org.springframework.transaction.support.TransactionSynchronization {
                    override fun afterCommit() {
                        deliver()
                    }
                }
            )
        } else {
            deliver()
        }
    }

    companion object {
        const val DOCTOR_ANDROID_CHANNEL_ID = "shifa_doctor_channel"

        /**
         * Tokens that will never succeed with this backend's Firebase project.
         * SenderId mismatch = token was issued by a different Firebase project.
         * Unregistered = app uninstalled or token rotated.
         */
        fun isPermanentTokenFailure(error: Exception): Boolean {
            if (error is FirebaseMessagingException) {
                when (error.messagingErrorCode) {
                    MessagingErrorCode.SENDER_ID_MISMATCH,
                    MessagingErrorCode.UNREGISTERED,
                    -> return true
                    else -> Unit
                }
            }
            return error.message.orEmpty().contains("SenderId mismatch", ignoreCase = true)
        }
    }
}
