package com.shifa.service

import com.shifa.domain.Appointment
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId

@Service
class AppointmentCancellationService(
    private val appts: AppointmentRepository,
    private val notifications: NotificationRepository,
    private val fcmService: FcmService,
    private val fulfillmentService: TreatmentPlanFulfillmentService,
) {

    /**
     * Doctor-initiated cancel from the calendar UI. Past appointments cannot be cancelled.
     */
    @Transactional
    fun cancelByDoctorManual(appointment: Appointment): Boolean {
        if (appointment.startAt.isBefore(Instant.now())) return false
        return cancelActiveAppointment(appointment)
    }

    /**
     * Cancel appointments that overlap an emergency schedule block (includes in-progress visits).
     */
    @Transactional
    fun cancelByDoctorForScheduleBlock(appointment: Appointment): Boolean {
        if (!appointment.endAt.isAfter(Instant.now())) return false
        return cancelActiveAppointment(appointment)
    }

    private fun cancelActiveAppointment(appointment: Appointment): Boolean {
        if (appointment.status == Appointment.Status.CANCELLED ||
            appointment.status == Appointment.Status.COMPLETED
        ) {
            return false
        }

        appointment.status = Appointment.Status.CANCELLED
        appts.save(appointment)

        try {
            fulfillmentService.revertFulfillmentsForCancelledAppointment(appointment.id)
        } catch (_: Exception) {
            // Cancellation must not fail if plan fulfillment rollback fails.
        }

        val zone = ZoneId.of(appointment.doctor.timeZone)
        val startLdt = appointment.startAt.atZone(zone).toLocalDateTime()
        val monthName = startLdt.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val dateStr = "${startLdt.dayOfMonth} $monthName ${startLdt.year}"

        val notif = com.shifa.domain.Notification(
            patient = appointment.patient,
            title = "Appointment Cancelled",
            message = "Doctor has cancelled your appointment on $dateStr. Please make another appointment.",
            type = com.shifa.domain.Notification.Type.APPOINTMENT_CANCELLED,
            appointmentId = appointment.id,
        )
        val savedNotif = notifications.save(notif)
        appointment.patient.fcmToken?.let { fcmService.sendPatientNotification(it, savedNotif) }
        return true
    }
}
