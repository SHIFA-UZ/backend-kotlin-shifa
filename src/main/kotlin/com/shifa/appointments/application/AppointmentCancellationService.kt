package com.shifa.appointments.application

import com.shifa.clinics.application.TreatmentPlanFulfillmentService

import com.shifa.appointments.domain.Appointment
import com.shifa.appointments.infrastructure.AppointmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId

@Service
class AppointmentCancellationService(
    private val appts: AppointmentRepository,
    private val notificationService: com.shifa.notifications.application.NotificationService,
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

        val notif = com.shifa.notifications.domain.Notification(
            patient = appointment.patient,
            title = "Appointment Cancelled",
            message = "Doctor has cancelled your appointment on $dateStr. Please make another appointment.",
            type = com.shifa.notifications.domain.Notification.Type.APPOINTMENT_CANCELLED,
            appointmentId = appointment.id,
        )
        val savedNotif = notificationService.save(notif)
        appointment.patient.fcmToken?.let { notificationService.sendPatientNotification(it, savedNotif) }
        return true
    }
}
