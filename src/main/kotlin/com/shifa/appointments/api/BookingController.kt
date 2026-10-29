// src/main/kotlin/com/shifa/appointments/api/BookingController.kt
package com.shifa.appointments.api

import com.shifa.appointments.api.dto.ApptDto
import com.shifa.appointments.api.dto.BookReq
import com.shifa.appointments.application.DoctorAppointmentBookingService
import com.shifa.appointments.application.DoctorBookingCommand
import com.shifa.clinics.application.ClinicAccessService
import com.shifa.shared.security.DoctorPrincipal
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException


@RestController
@RequestMapping("/api/schedule/book")
class BookingController(
    private val doctors: com.shifa.doctors.application.DoctorProfiles,
    private val users: com.shifa.auth.application.Users,
    private val clinicAccess: ClinicAccessService,
    private val bookingService: DoctorAppointmentBookingService,
) {

    @PostMapping
    fun book(
        @AuthenticationPrincipal principal: Any,
        @RequestBody b: BookReq
    ): ApptDto {

        clinicAccess.assertPracticeActor(principal)

        val resourceDoctorId = b.resourceDoctorId ?: when (principal) {
            is DoctorPrincipal -> principal.profile.id
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "resourceDoctorId is required")
        }
        clinicAccess.assertCanViewDoctorCalendar(principal, resourceDoctorId)

        val doctor = doctors.findById(resourceDoctorId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found")
        }
        val bookedBy = users.findById(clinicAccess.resolveBookingActorUserId(principal)).orElse(null)
        val patientId = b.patientId
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "patientId is required")
        val saved = try {
            bookingService.book(
                doctor = doctor,
                actor = bookedBy,
                command = DoctorBookingCommand(
                    patientId = patientId,
                    startAt = Instant.parse(b.startAt),
                    slotMinutes = b.slotMinutes,
                    locationId = b.locationId,
                    location = b.location,
                    reason = b.reason,
                    isVideo = b.isVideo,
                ),
            )
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message)
        }

        return ApptDto(
            id = saved.id,
            startAt = saved.startAt.toString(),
            endAt = saved.endAt.toString(),
            patientId = saved.patient.id,
            patientName = saved.patient.fullName,
            location = saved.location,
            reason = saved.reason,
            status = saved.status.name,
            locationId = saved.locationRef?.id,
            locationLabel = saved.locationRef?.label
        )
    }
}
