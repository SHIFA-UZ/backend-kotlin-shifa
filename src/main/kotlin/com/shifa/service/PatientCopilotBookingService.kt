package com.shifa.service

import com.shifa.config.AppProperties
import com.shifa.domain.Appointment
import com.shifa.domain.Notification
import com.shifa.domain.PatientProfile
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DoctorLocationRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.NotificationRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.ZoneId

@Service
class PatientCopilotBookingService(
    private val doctorProfiles: DoctorProfileRepository,
    private val appointments: AppointmentRepository,
    private val notifications: NotificationRepository,
    private val doctorLocations: DoctorLocationRepository,
    private val fcmService: FcmService,
    private val appProps: AppProperties,
    private val patientDaySlotsService: PatientDaySlotsService
) {

    data class BookedAppointmentDto(
        val id: Long?,
        val doctorId: Long?,
        val doctorName: String?,
        val doctorProfession: String?,
        val doctorClinic: String?,
        val doctorPhotoUrl: String?,
        val startAt: String,
        val endAt: String,
        val location: String,
        val reason: String?,
        val status: String,
        val paymentStatus: String,
        val paymentAmountMinor: Long?,
        val paymentCurrency: String?
    )

    /**
     * Books the **nearest** bookable slot to [preferredStartAt] within the next [maxDaysAhead] calendar days
     * (in the doctor's timezone), using the same overlap rules as manual patient booking.
     *
     * @param consentConfirmed must be true (caller validates JSON); defense in depth.
     */
    fun bookNearestToPreferred(
        patient: PatientProfile,
        doctorId: Long,
        preferredStartAt: Instant,
        isVideo: Boolean,
        reason: String?,
        consentConfirmed: Boolean,
        maxDaysAhead: Long = 28L
    ): BookedAppointmentDto {
        if (!consentConfirmed) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Patient consent is required for auto-booking")
        }

        val patientId = patient.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient ID not found")

        val doctor = doctorProfiles.findById(doctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }

        if (!doctor.user.enabled) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found")
        }

        val zone = ZoneId.of(doctor.timeZone)
        var startLocalDate = preferredStartAt.atZone(zone).toLocalDate()
        var bestStart: Instant? = null
        var bestSlotMinutes: Int? = null
        var bestSlotLocationId: Long? = null
        var bestDelta = Long.MAX_VALUE

        var dayOffset = 0L
        while (dayOffset < maxDaysAhead) {
            val d = startLocalDate.plusDays(dayOffset)
            val slots = patientDaySlotsService.availableSlotsForDay(doctor, d)
            for (slot in slots) {
                val start = Instant.parse(slot.startAt)
                val delta = kotlin.math.abs(start.epochSecond - preferredStartAt.epochSecond)
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestStart = start
                    bestSlotMinutes = slot.slotMinutes
                    bestSlotLocationId = slot.locationId
                }
            }
            dayOffset++
        }

        val chosenStart = bestStart
            ?: throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "No available appointment slots found for this doctor in the next $maxDaysAhead days"
            )
        val slotMinutes = bestSlotMinutes ?: 30
        val endAt = chosenStart.plusSeconds(slotMinutes * 60L)

        if (appointments.findOverlapping(doctor.id!!, chosenStart, endAt).isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Time slot is already booked")
        }

        val patientOverlapping = appointments.findOverlappingForPatient(patientId, chosenStart, endAt)
        if (patientOverlapping.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "You already have an appointment scheduled at this date and time. Please choose a different time."
            )
        }

        // Use the slot's location if the doctor has structured locations. Fall back to primary
        // location for legacy/ambiguous situations. Video consults don't attach a location.
        val locationRef = when {
            isVideo -> null
            bestSlotLocationId != null ->
                doctorLocations.findByDoctorIdOrderByIsPrimaryDescIdAsc(doctor.id!!)
                    .firstOrNull { it.id == bestSlotLocationId }
            else -> doctorLocations.findByDoctorIdAndIsPrimaryTrue(doctor.id!!).orElse(null)
        }

        val location = when {
            isVideo -> "Video Consultation"
            locationRef != null -> locationRef.clinic?.takeIf { it.isNotBlank() } ?: locationRef.label
            else -> doctor.clinic ?: "Clinic"
        }

        val saved = appointments.save(
            Appointment(
                doctor = doctor,
                patient = patient,
                startAt = chosenStart,
                endAt = endAt,
                location = location,
                locationRef = locationRef,
                reason = reason,
                status = if (doctor.consultationPriceMinor != null) {
                    Appointment.Status.REQUESTED
                } else {
                    Appointment.Status.CONFIRMED
                },
                paymentAmountMinor = doctor.consultationPriceMinor,
                paymentCurrency = doctor.consultationCurrency,
                paymentStatus = if (doctor.consultationPriceMinor != null) {
                    Appointment.PaymentStatus.PENDING
                } else {
                    Appointment.PaymentStatus.NOT_REQUIRED
                }
            )
        )

        val bookedMessage = NotificationFormatting.patientBookedMessage(
            patientName = patient.fullName ?: "Patient",
            startAt = saved.startAt,
            timeZone = doctor.timeZone,
            suffix = "(Shifa AI)",
        )
        val notif = Notification(
            patient = null,
            doctor = doctor,
            title = "New Appointment Booked",
            message = bookedMessage,
            type = Notification.Type.APPOINTMENT_BOOKED_BY_PATIENT,
            appointmentId = saved.id
        )
        val savedNotif = notifications.save(notif)
        doctor.fcmToken?.let { fcmService.sendDoctorNotification(it, savedNotif) }

        return BookedAppointmentDto(
            id = saved.id,
            doctorId = saved.doctor.id,
            doctorName = "${saved.doctor.firstName} ${saved.doctor.lastName}".trim(),
            doctorProfession = saved.doctor.profession,
            doctorClinic = saved.doctor.clinic,
            doctorPhotoUrl = normalizeAvatarUrl(saved.doctor.avatarUrl, appProps.publicBaseUrl),
            startAt = saved.startAt.toString(),
            endAt = saved.endAt.toString(),
            location = saved.location,
            reason = saved.reason,
            status = saved.status.name,
            paymentStatus = saved.paymentStatus.name,
            paymentAmountMinor = saved.paymentAmountMinor,
            paymentCurrency = saved.paymentCurrency
        )
    }

    private fun normalizeAvatarUrl(avatarUrl: String?, baseUrl: String): String? {
        val trimmed = avatarUrl?.trim() ?: return null
        val isAbs = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        return if (isAbs) trimmed
        else "${baseUrl.removeSuffix("/")}/${trimmed.removePrefix("/")}"
    }
}
