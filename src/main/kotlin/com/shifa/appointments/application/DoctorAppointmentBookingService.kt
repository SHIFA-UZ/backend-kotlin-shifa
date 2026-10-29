package com.shifa.appointments.application


import com.shifa.appointments.domain.Appointment
import com.shifa.appointments.infrastructure.AppointmentRepository
import com.shifa.auth.domain.User
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.notifications.domain.Notification
import java.time.Instant
import java.time.ZoneId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class DoctorBookingCommand(
    val patientId: Long,
    val startAt: Instant,
    val slotMinutes: Int,
    val locationId: Long? = null,
    val location: String? = null,
    val reason: String? = null,
    val isVideo: Boolean = false,
)

@Service
class DoctorAppointmentBookingService(
    private val patients: com.shifa.patients.application.PatientProfiles,
    private val appointments: AppointmentRepository,
    private val notificationService: com.shifa.notifications.application.NotificationService,
    private val doctorLocations: com.shifa.doctors.application.DoctorLocations,
    private val slotAvailabilityService: SlotAvailabilityService,
) {
    @Transactional
    fun book(doctor: DoctorProfile, actor: User?, command: DoctorBookingCommand): Appointment {
        require(command.slotMinutes in 5..480) { "Invalid appointment duration" }
        val patient = patients.findById(command.patientId)
            .orElseThrow { NoSuchElementException("Patient not found: ${command.patientId}") }
        val endAt = command.startAt.plusSeconds(command.slotMinutes * 60L)

        check(appointments.findOverlapping(doctor.id, command.startAt, endAt).isEmpty()) {
            "Overlapping appointment exists"
        }
        check(appointments.findOverlappingForPatient(command.patientId, command.startAt, endAt).isEmpty()) {
            "Patient already has an appointment scheduled at this date and time"
        }

        val doctorLocs = doctorLocations.findByDoctorIdOrderByIsPrimaryDescIdAsc(doctor.id)
        val locationRef = when {
            command.isVideo -> null
            command.locationId != null -> doctorLocs.firstOrNull { it.id == command.locationId }
                ?: throw IllegalArgumentException("Invalid locationId for this doctor")
            doctorLocs.size == 1 -> doctorLocs.first()
            doctorLocs.isNotEmpty() -> doctorLocs.firstOrNull { it.isPrimary } ?: doctorLocs.first()
            else -> null
        }
        slotAvailabilityService.assertBookableConsecutiveRange(
            doctor = doctor,
            startAt = command.startAt,
            endAt = endAt,
            filterLocationId = if (command.isVideo) null else locationRef?.id,
            excludeAppointmentId = null,
        )
        val location = when {
            command.isVideo -> "Video Consultation"
            locationRef != null -> locationRef.clinic?.takeIf { it.isNotBlank() } ?: locationRef.label
            !command.location.isNullOrBlank() -> command.location
            else -> "Clinic"
        }
        val saved = appointments.save(
            Appointment(
                doctor = doctor,
                patient = patient,
                bookedByUser = actor,
                startAt = command.startAt,
                endAt = endAt,
                location = location,
                locationRef = locationRef,
                reason = command.reason,
                isVideoConsultation = command.isVideo,
                status = Appointment.Status.CONFIRMED,
                paymentStatus = Appointment.PaymentStatus.NOT_REQUIRED,
            ),
        )
        val local = saved.startAt.atZone(ZoneId.of(doctor.timeZone))
        val notification = notificationService.save(
            Notification(
                patient = patient,
                title = "New Appointment Scheduled",
                message = "Doctor has scheduled an appointment for you on " +
                    "${local.dayOfMonth} ${local.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${local.year} " +
                    "at ${"%02d:%02d".format(local.hour, local.minute)}. Location: $location.",
                type = Notification.Type.APPOINTMENT_REMINDER,
                appointmentId = saved.id,
            ),
        )
        patient.fcmToken?.let { notificationService.sendPatientNotification(it, notification) }
        return saved
    }
}
