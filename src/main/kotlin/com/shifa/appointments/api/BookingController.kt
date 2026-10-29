// src/main/kotlin/com/shifa/appointments/api/BookingController.kt
package com.shifa.appointments.api

import com.shifa.doctors.infrastructure.DoctorProfileRepository
import com.shifa.auth.infrastructure.UserRepository
import com.shifa.shared.security.DoctorPrincipal
import com.shifa.clinics.application.ClinicAccessService
import com.shifa.appointments.application.DoctorAppointmentBookingService
import com.shifa.appointments.application.DoctorBookingCommand
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
@RequestMapping("/api/schedule/book")
class BookingController(
    private val doctors: DoctorProfileRepository,
    private val users: UserRepository,
    private val clinicAccess: ClinicAccessService,
    private val bookingService: DoctorAppointmentBookingService,
) {

    /** startAt: ISO 8601 UTC (e.g. 2026-02-12T13:00:00Z). No timezone guessing. */
    data class BookReq(
        val startAt: String,
        val slotMinutes: Int,
        val patientId: Long?,
        val location: String?,
        val reason: String?,
        val isVideo: Boolean,
        /** Optional structured location FK. Required when doctor has multiple locations and !isVideo. */
        val locationId: Long? = null,
        /** Calendar doctor receiving the booking (defaults to authenticated doctor). */
        val resourceDoctorId: Long? = null
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
        val status: String,
        val locationId: Long? = null,
        val locationLabel: String? = null
    )

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
