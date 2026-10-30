package com.shifa.notifications.application

import com.shifa.appointments.domain.Appointment
import com.shifa.documents.domain.DocumentAccessRequest
import com.shifa.notifications.domain.Notification
import com.shifa.notifications.infrastructure.NotificationRepository
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

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

    /** The feed with its details, loaded with one query per referenced record type instead of two per notification (roadmap P5). */
    @Transactional(readOnly = true)
    fun list(owner: Owner): List<NotificationDetails> {
        val all = all(owner)
        val requests = documentAccess.requestsWithDetails(all.mapNotNull { it.documentAccessRequestId })
        val appointments = appointments.findAllById(all.mapNotNull { it.appointmentId }.toSet()).associateBy { it.id }
        return all.map { details(it, requests[it.documentAccessRequestId], appointments[it.appointmentId]) }
    }

    @Transactional(readOnly = true)
    fun unreadCount(owner: Owner): Int = when (owner) {
        is Owner.Doctor -> notifications.countByDoctor_IdAndReadAtIsNull(owner.doctorId)
        is Owner.Patient -> notifications.countByPatient_IdAndReadAtIsNull(owner.patientId)
    }.toInt()

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
        val now = Instant.now()
        when (owner) {
            is Owner.Doctor -> notifications.markAllReadForDoctor(owner.doctorId, now)
            is Owner.Patient -> notifications.markAllReadForPatient(owner.patientId, now)
        }
    }

    private fun all(owner: Owner): List<Notification> = when (owner) {
        is Owner.Doctor -> notifications.findByDoctor_IdOrderByCreatedAtDesc(owner.doctorId)
        is Owner.Patient -> notifications.findByPatient_IdOrderByCreatedAtDesc(owner.patientId)
    }

    private fun details(n: Notification, request: DocumentAccessRequest?, appointment: Appointment?): NotificationDetails {
        // Fetch-joined: open-in-view is off, so lazy associations cannot be read here otherwise.
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
