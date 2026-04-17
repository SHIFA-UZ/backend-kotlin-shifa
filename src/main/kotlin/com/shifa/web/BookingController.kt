// src/main/kotlin/com/shifa/web/BookingController.kt
package com.shifa.web

import com.shifa.domain.Appointment
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.NotificationRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.DoctorPrincipal
import com.shifa.service.FcmService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@RestController
@RequestMapping("/api/schedule/book")
class BookingController(
    private val patients: PatientProfileRepository,
    private val appts: AppointmentRepository,
    private val notifications: NotificationRepository,
    private val fcmService: FcmService
) {

    /** startAt: ISO 8601 UTC (e.g. 2026-02-12T13:00:00Z). No timezone guessing. */
    data class BookReq(
        val startAt: String,
        val slotMinutes: Int,
        val patientId: Long?,
        val location: String?,
        val reason: String?,
        val isVideo: Boolean
    )

    /** startAt/endAt: ISO 8601 UTC. */
    data class ApptDto(
        val id: Long?,
        val startAt: String,
        val endAt: String,
        val patientId: Long?,
        val patientName: String,
        val location: String,
        val reason: String?,
        val status: String
    )

    @PostMapping
    fun book(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody b: BookReq
    ): ApptDto {

        val doctor = principal.profile
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        val patientId = b.patientId
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "patientId is required")

        val patient = patients.findById(patientId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found: $patientId")
            }

        val startAt = Instant.parse(b.startAt)
        val endAt = startAt.plusSeconds(b.slotMinutes * 60L)

        if (appts.findOverlapping(doctor.id!!, startAt, endAt).isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Overlapping appointment exists")
        }

        val patientOverlapping = appts.findOverlappingForPatient(patientId, startAt, endAt)
        if (patientOverlapping.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Patient already has an appointment scheduled at this date and time. Please choose a different time slot."
            )
        }

        val location = when {
            b.isVideo -> "Video Consultation"
            !b.location.isNullOrBlank() -> b.location
            else -> "Clinic"
        }

        val saved = appts.save(
            Appointment(
                doctor = doctor,
                patient = patient,
                startAt = startAt,
                endAt = endAt,
                location = location,
                reason = b.reason,
                status = Appointment.Status.CONFIRMED
            )
        )

        val zone = ZoneId.of(doctor.timeZone)
        val startZ = saved.startAt.atZone(zone)
        val monthName = startZ.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val dateStr = "${startZ.dayOfMonth} $monthName ${startZ.year}"
        val timeStr = "${startZ.hour.toString().padStart(2, '0')}:${startZ.minute.toString().padStart(2, '0')}"
        val notif = com.shifa.domain.Notification(
            patient = patient,
            title = "New Appointment Scheduled",
            message = "Doctor has scheduled an appointment for you on $dateStr at $timeStr. Location: $location.",
            type = com.shifa.domain.Notification.Type.APPOINTMENT_REMINDER,
            appointmentId = saved.id
        )
        val savedNotif = notifications.save(notif)
        patient.fcmToken?.let { fcmService.sendPatientNotification(it, savedNotif) }

        return ApptDto(
            id = saved.id,
            startAt = saved.startAt.toString(),
            endAt = saved.endAt.toString(),
            patientId = patient.id,
            patientName = patient.fullName ?: "",
            location = saved.location,
            reason = saved.reason,
            status = saved.status.name
        )
    }
}
