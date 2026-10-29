// src/main/kotlin/com/shifa/notifications/api/NotificationController.kt
package com.shifa.notifications.api

import com.shifa.documents.domain.DocumentAccessRequest
import com.shifa.notifications.domain.Notification
import com.shifa.documents.infrastructure.DocumentAccessRequestRepository
import com.shifa.notifications.infrastructure.NotificationRepository
import com.shifa.appointments.infrastructure.AppointmentRepository
import com.shifa.shared.security.ClinicStaffPrincipal
import com.shifa.shared.security.DoctorPrincipal
import com.shifa.shared.security.PatientPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
@RequestMapping("/api/notifications")
class NotificationController(
    private val notifications: NotificationRepository,
    private val documentAccessRequestRepo: DocumentAccessRequestRepository,
    private val appointments: AppointmentRepository,
    private val currentPatient: com.shifa.patients.application.CurrentPatientResolver,
) {

    private fun currentPatientProfile(principal: PatientPrincipal): com.shifa.patients.domain.PatientProfile = currentPatient.resolve(principal)

    data class NotificationDto(
        val id: Long,
        val title: String,
        val message: String,
        val type: String,
        val appointmentId: Long?,
        val patientFormId: Long? = null,
        val documentAccessRequestId: Long?,
        val taskId: Long? = null,
        val documentId: Long? = null,
        val patientId: Long? = null,
        val documentAccessRequestStatus: String? = null,
        val patientName: String? = null,
        val documentTitle: String? = null,
        val requestingDoctorName: String? = null,
        val createdAt: String,
        val readAt: String?,
        val treatmentPlanId: Long? = null,
        val appointmentStartAt: String? = null,
    )

    @GetMapping
    fun getNotifications(
        @AuthenticationPrincipal principal: Any
    ): List<NotificationDto> {
        val notifs = when (principal) {
            is ClinicStaffPrincipal -> emptyList()
            is DoctorPrincipal -> notifications.findByDoctor_IdOrderByCreatedAtDesc(principal.profile.id)
            is PatientPrincipal -> {
                val patient = currentPatientProfile(principal)
                val patientId = patient.id ?: throw ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Patient profile not found"
                )
                notifications.findByPatient_IdOrderByCreatedAtDesc(patientId)
            }
            else -> emptyList()
        }
        return notifs.map { n ->
            // Fetch-joined: open-in-view is off, so lazy associations cannot be read here otherwise
            // (the former try/catch silently dropped the names on LazyInitializationException).
            val req = n.documentAccessRequestId?.let { documentAccessRequestRepo.findWithDetailsById(it) }
            val requestStatus = req?.status?.name
            val docPatientName = req?.document?.patient?.fullName
            val documentTitle = req?.document?.title
            val requestingDoctorName = req?.let { "${it.requestingDoctor.firstName} ${it.requestingDoctor.lastName}".trim() }
            val appointment = n.appointmentId?.let { apptId ->
                appointments.findById(apptId).orElse(null)
            }
            val appointmentStartAt = appointment?.startAt?.toString()
            val patientName = docPatientName
                ?: appointment?.patient?.fullName
                ?: (if (n.type == Notification.Type.APPOINTMENT_BOOKED_BY_PATIENT) n.patient?.fullName else null)
            NotificationDto(
                id = n.id,
                title = n.title,
                message = n.message,
                type = n.type.name,
                appointmentId = n.appointmentId,
                patientFormId = n.patientFormId,
                documentAccessRequestId = n.documentAccessRequestId,
                taskId = n.taskId,
                documentId = n.documentId,
                patientId = n.documentPatientId,
                documentAccessRequestStatus = requestStatus,
                patientName = patientName,
                documentTitle = documentTitle,
                requestingDoctorName = requestingDoctorName,
                createdAt = n.createdAt.toString(),
                readAt = n.readAt?.toString(),
                treatmentPlanId = n.treatmentPlanId,
                appointmentStartAt = appointmentStartAt,
            )
        }
    }

    @GetMapping("/unread/count")
    fun getUnreadCount(
        @AuthenticationPrincipal principal: Any
    ): Map<String, Int> {
        val notifs = when (principal) {
            is ClinicStaffPrincipal -> emptyList()
            is DoctorPrincipal -> notifications.findByDoctor_IdOrderByCreatedAtDesc(principal.profile.id)
            is PatientPrincipal -> {
                val patient = currentPatientProfile(principal)
                val patientId = patient.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found")
                notifications.findByPatient_IdOrderByCreatedAtDesc(patientId)
            }
            else -> emptyList()
        }
        val unreadCount = notifs.count { !it.isRead() }
        return mapOf("count" to unreadCount)
    }

    @PutMapping("/{notificationId}/read")
    fun markAsRead(
        @AuthenticationPrincipal principal: Any,
        @PathVariable notificationId: Long
    ) {
        val notification = notifications.findById(notificationId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found: $notificationId")
            }
        val belongs = when (principal) {
            is DoctorPrincipal -> notification.doctor?.id == principal.profile.id
            is PatientPrincipal -> {
                val patient = currentPatientProfile(principal)
                notification.patient?.id == patient.id
            }
            else -> false
        }
        if (!belongs) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Notification does not belong to you")
        }
        notification.readAt = Instant.now()
        notifications.save(notification)
    }

    @PutMapping("/read-all")
    fun markAllAsRead(
        @AuthenticationPrincipal principal: Any
    ) {
        val notifs = when (principal) {
            is ClinicStaffPrincipal -> emptyList()
            is DoctorPrincipal -> notifications.findByDoctor_IdOrderByCreatedAtDesc(principal.profile.id)
            is PatientPrincipal -> {
                val patient = currentPatientProfile(principal)
                val patientId = patient.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found")
                notifications.findByPatient_IdOrderByCreatedAtDesc(patientId)
            }
            else -> emptyList()
        }.filter { !it.isRead() }
        val now = Instant.now()
        notifs.forEach { it.readAt = now }
        notifications.saveAll(notifs)
    }
}
