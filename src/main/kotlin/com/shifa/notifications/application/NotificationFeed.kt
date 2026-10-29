package com.shifa.notifications.application

import com.shifa.notifications.domain.Notification
import com.shifa.notifications.infrastructure.NotificationRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/** A notification with the names and dates the feed shows next to it, read from the referenced records. */
data class NotificationDetails(
    val notification: Notification,
    val documentAccessRequestStatus: String?,
    val patientName: String?,
    val documentTitle: String?,
    val requestingDoctorName: String?,
    val appointmentStartAt: Instant?,
)

/** The signed-in user's notification feed: list, unread count, mark read. Owners are a doctor id or a patient id. */
@Service
class NotificationFeed(
    private val notifications: NotificationRepository,
    private val documentAccess: com.shifa.documents.application.DocumentAccessService,
    private val appointments: com.shifa.appointments.application.Appointments,
) {
    sealed interface Owner {
        data class Doctor(val doctorId: Long) : Owner
        data class Patient(val patientId: Long) : Owner
    }

    @Transactional(readOnly = true)
    fun list(owner: Owner): List<NotificationDetails> = all(owner).map(::details)

    @Transactional(readOnly = true)
    fun unreadCount(owner: Owner): Int = all(owner).count { !it.isRead() }

    /** [owner] is null for principals without a feed (clinic staff): the lookup still happens first, so 404 precedes 403. */
    @Transactional
    fun markRead(owner: Owner?, notificationId: Long) {
        val notification = notifications.findById(notificationId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found: $notificationId") }
        val belongs = when (owner) {
            is Owner.Doctor -> notification.doctor?.id == owner.doctorId
            is Owner.Patient -> notification.patient?.id == owner.patientId
            null -> false
        }
        if (!belongs) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Notification does not belong to you")
        notification.readAt = Instant.now()
        notifications.save(notification)
    }

    @Transactional
    fun markAllRead(owner: Owner) {
        val unread = all(owner).filter { !it.isRead() }
        val now = Instant.now()
        unread.forEach { it.readAt = now }
        notifications.saveAll(unread)
    }

    private fun all(owner: Owner): List<Notification> = when (owner) {
        is Owner.Doctor -> notifications.findByDoctor_IdOrderByCreatedAtDesc(owner.doctorId)
        is Owner.Patient -> notifications.findByPatient_IdOrderByCreatedAtDesc(owner.patientId)
    }

    private fun details(n: Notification): NotificationDetails {
        // Fetch-joined: open-in-view is off, so lazy associations cannot be read here otherwise.
        val request = n.documentAccessRequestId?.let { documentAccess.requestWithDetails(it) }
        val appointment = n.appointmentId?.let { appointments.findById(it).orElse(null) }
        val patientName = request?.document?.patient?.fullName
            ?: appointment?.patient?.fullName
            ?: (if (n.type == Notification.Type.APPOINTMENT_BOOKED_BY_PATIENT) n.patient?.fullName else null)
        return NotificationDetails(
            notification = n,
            documentAccessRequestStatus = request?.status?.name,
            patientName = patientName,
            documentTitle = request?.document?.title,
            requestingDoctorName = request?.let { "${it.requestingDoctor.firstName} ${it.requestingDoctor.lastName}".trim() },
            appointmentStartAt = appointment?.startAt,
        )
    }
}
