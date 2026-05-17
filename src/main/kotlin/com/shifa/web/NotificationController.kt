// src/main/kotlin/com/shifa/web/NotificationController.kt
package com.shifa.web

import com.shifa.domain.DocumentAccessRequest
import com.shifa.domain.Notification
import com.shifa.repo.DocumentAccessRequestRepository
import com.shifa.repo.NotificationRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.ClinicStaffPrincipal
import com.shifa.security.DoctorPrincipal
import com.shifa.security.PatientPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
@RequestMapping("/api/notifications")
class NotificationController(
    private val notifications: NotificationRepository,
    private val patientProfiles: PatientProfileRepository,
    private val documentAccessRequestRepo: DocumentAccessRequestRepository
) {

    private fun currentPatientProfile(principal: PatientPrincipal): com.shifa.domain.PatientProfile {
        val user = principal.user
        return user.phone?.let { patientProfiles.findByPhone(it) }
            ?.orElseGet {
                user.email?.let { patientProfiles.findByEmail(it) }
                    ?.orElse(null)
            }
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Patient profile not found for user ${user.id}"
            )
    }

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
            val req = n.documentAccessRequestId?.let { reqId ->
                documentAccessRequestRepo.findById(reqId).orElse(null)
            }
            val requestStatus = req?.status?.name
            val (docPatientName, documentTitle, requestingDoctorName) = try {
                when (req) {
                    null -> Triple(null, null, null)
                    else -> Triple(
                        req.document.patient?.fullName,
                        req.document.title,
                        "${req.requestingDoctor.firstName} ${req.requestingDoctor.lastName}".trim()
                    )
                }
            } catch (_: Exception) {
                Triple(null, null, null)
            }
            val patientName = docPatientName
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
                treatmentPlanId = n.treatmentPlanId
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
