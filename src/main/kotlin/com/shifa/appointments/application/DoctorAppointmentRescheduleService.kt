package com.shifa.appointments.application


import com.shifa.appointments.domain.Appointment
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.notifications.domain.Notification
import com.shifa.appointments.infrastructure.AppointmentRepository
import com.shifa.doctors.infrastructure.DoctorLocationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

data class DoctorRescheduleCommand(
    val appointmentId: Long,
    val expectedStartAt: Instant,
    val expectedStatus: Appointment.Status,
    val newStartAt: Instant,
    val slotMinutes: Int,
    val locationId: Long? = null,
)

@Service
class DoctorAppointmentRescheduleService(
    private val appointments: AppointmentRepository,
    private val notificationService: com.shifa.notifications.application.NotificationService,
    private val doctorLocations: DoctorLocationRepository,
    private val slotAvailabilityService: SlotAvailabilityService,
    private val clock: Clock,
) {
    @Transactional
    fun reschedule(doctor: DoctorProfile, command: DoctorRescheduleCommand): Appointment {
        require(command.slotMinutes in 5..480) { "Invalid appointment duration" }
        val appointment = appointments.findById(command.appointmentId)
            .orElseThrow { NoSuchElementException("Appointment not found") }
        check(appointment.doctor.id == doctor.id) { "Appointment is not available" }
        check(appointment.startAt == command.expectedStartAt && appointment.status == command.expectedStatus) {
            "The appointment changed after the proposal was created"
        }
        check(appointment.status == Appointment.Status.REQUESTED ||
            appointment.status == Appointment.Status.CONFIRMED
        ) { "Appointment status ${appointment.status.name} is not reschedulable" }
        val now = clock.instant()
        check(!appointment.startAt.isBefore(now)) { "Cannot change a past appointment" }
        check(!command.newStartAt.isBefore(now)) { "Cannot move appointment to a past date or time" }

        val newEndAt = command.newStartAt.plusSeconds(command.slotMinutes * 60L)
        check(
            appointments.findOverlapping(doctor.id, command.newStartAt, newEndAt)
                .none { it.id != appointment.id },
        ) { "Overlapping appointment exists" }
        check(
            appointments.findOverlappingForPatient(
                requireNotNull(appointment.patient.id) { "Patient not found" },
                command.newStartAt,
                newEndAt,
            ).none { it.id != appointment.id },
        ) { "Patient already has an appointment scheduled at this date and time" }

        val doctorLocs = doctorLocations.findByDoctorIdOrderByIsPrimaryDescIdAsc(doctor.id)
        val movedLocation = command.locationId?.let { locationId ->
            doctorLocs.firstOrNull { it.id == locationId }
                ?: throw IllegalArgumentException("Invalid locationId for this doctor")
        }
        slotAvailabilityService.assertBookableConsecutiveRange(
            doctor = doctor,
            startAt = command.newStartAt,
            endAt = newEndAt,
            filterLocationId = movedLocation?.id ?: appointment.locationRef?.id,
            excludeAppointmentId = appointment.id,
        )

        val zone = ZoneId.of(doctor.timeZone)
        val oldLocal = appointment.startAt.atZone(zone).toLocalDateTime()
        val newLocal = command.newStartAt.atZone(zone).toLocalDateTime()
        if (movedLocation != null) {
            appointment.locationRef = movedLocation
            appointment.location = movedLocation.clinic?.takeIf { it.isNotBlank() } ?: movedLocation.label
        }
        appointment.startAt = command.newStartAt
        appointment.endAt = newEndAt
        appointment.smsReminderSentAt = null
        val saved = appointments.save(appointment)

        val notification = notificationService.save(
            Notification(
                patient = saved.patient,
                title = "Appointment Changed",
                message = "Doctor changed your appointment from ${dateLabel(oldLocal)} to ${dateLabel(newLocal)}. " +
                    "If this time does not work, please reschedule in the app.",
                type = Notification.Type.APPOINTMENT_CHANGED,
                appointmentId = saved.id,
            ),
        )
        saved.patient.fcmToken?.let { notificationService.sendPatientNotification(it, notification) }
        return saved
    }

    private fun dateLabel(value: java.time.LocalDateTime): String {
        val month = value.month.name.lowercase().replaceFirstChar { it.uppercase() }
        return "${value.dayOfMonth} $month ${value.year} at ${"%02d:%02d".format(value.hour, value.minute)}"
    }
}
