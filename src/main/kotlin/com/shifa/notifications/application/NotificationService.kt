package com.shifa.notifications.application

import com.shifa.notifications.domain.Notification
import com.shifa.notifications.domain.DevSmsSendResult
import com.shifa.notifications.infrastructure.DevSmsService
import com.shifa.notifications.infrastructure.FcmService
import com.shifa.notifications.infrastructure.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Optional

/**
 * The one door into notifications for the other features (roadmap Phase 5 step 4): recording a notification, pushing
 * it to a device, sending an SMS. Nothing outside this feature touches [NotificationRepository], [FcmService] or
 * [DevSmsService] directly, so per-device tokens (Phase 4 item 2) and localised push texts (item 1) are changes here only.
 * Method names match the former repository and gateway calls on purpose; the callers only changed the receiver.
 */
@Service
class NotificationService(
    private val notifications: NotificationRepository,
    private val fcm: FcmService,
    private val sms: DevSmsService,
) {
    // --- records
    fun save(notification: Notification): Notification = notifications.save(notification)
    fun saveAll(all: Iterable<Notification>): List<Notification> = notifications.saveAll(all)
    fun findById(id: Long): Optional<Notification> = notifications.findById(id)
    /** Forces the INSERT so the generated id can go into a push payload before the transaction ends. */
    fun flush() = notifications.flush()
    @Transactional(readOnly = true)
    fun forDoctor(doctorId: Long): List<Notification> = notifications.findByDoctor_IdOrderByCreatedAtDesc(doctorId)
    @Transactional(readOnly = true)
    fun forPatient(patientId: Long): List<Notification> = notifications.findByPatient_IdOrderByCreatedAtDesc(patientId)
    @Transactional
    fun deleteAllForDoctor(doctorId: Long) = notifications.deleteAll(notifications.findByDoctor_IdOrderByCreatedAtDesc(doctorId))
    @Transactional
    fun deleteAllForPatient(patientId: Long) = notifications.deleteAll(notifications.findByPatient_IdOrderByCreatedAtDesc(patientId))
    fun alreadyNotifiedPatient(patientId: Long, appointmentId: Long, type: Notification.Type): Boolean =
        notifications.existsByPatient_IdAndAppointmentIdAndType(patientId, appointmentId, type)
    fun alreadyNotifiedDoctor(doctorId: Long, appointmentId: Long, type: Notification.Type): Boolean =
        notifications.existsByDoctor_IdAndAppointmentIdAndType(doctorId, appointmentId, type)

    // --- push
    fun sendPatientNotification(fcmToken: String?, notification: Notification, extraData: Map<String, String> = emptyMap()): Boolean =
        fcm.sendPatientNotification(fcmToken, notification, extraData)
    fun sendDoctorNotification(fcmToken: String?, notification: Notification, extraData: Map<String, String> = emptyMap()): Boolean =
        fcm.sendDoctorNotification(fcmToken, notification, extraData)
    fun sendPatientNotificationAfterCommit(fcmToken: String?, notification: Notification, extraData: Map<String, String> = emptyMap()) =
        fcm.sendPatientNotificationAfterCommit(fcmToken, notification, extraData)
    fun sendDoctorNotificationAfterCommit(fcmToken: String?, notification: Notification, extraData: Map<String, String> = emptyMap()) =
        fcm.sendDoctorNotificationAfterCommit(fcmToken, notification, extraData)

    // --- sms
    fun isSmsConfigured(): Boolean = sms.isConfigured()
    fun sendSms(phone: String, message: String): DevSmsSendResult = sms.sendSms(phone, message)
}
