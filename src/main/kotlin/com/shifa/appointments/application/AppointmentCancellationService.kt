package com.shifa.appointments.application

import com.shifa.clinics.application.TreatmentPlanFulfillmentService

import com.shifa.appointments.domain.Appointment
import com.shifa.appointments.infrastructure.AppointmentRepository
import com.shifa.notifications.application.PatientAppointmentPushI18n
import com.shifa.notifications.domain.Notification
import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import org.springframework.http.HttpStatus
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
     * Doctor-initiated cancel from the calendar UI. Past appointments cannot be cancelled. With a [reason] (and an
     * optional [note]) both are stored and the patient's notification says why, in the patient's language.
     */
    @Transactional
    fun cancelByDoctorManual(
        appointment: Appointment,
        reason: Appointment.CancelReason? = null,
        note: String? = null,
    ): Boolean {
        if (appointment.startAt.isBefore(Instant.now())) return false
        return cancelActiveAppointment(appointment, reason, note)
    }

    /**
     * Cancel appointments that overlap an emergency schedule block (includes in-progress visits).
     */
    @Transactional
    fun cancelByDoctorForScheduleBlock(appointment: Appointment): Boolean {
        if (!appointment.endAt.isAfter(Instant.now())) return false
        return cancelActiveAppointment(appointment, null, null)
    }

    /**
     * The patient did not come: a confirmed or started visit becomes NO_SHOW (see [Appointment.canMarkNoShow]),
     * else 409 `NO_SHOW_NOT_ALLOWED`. The patient is told (inbox and push) unless [notify] is false. Payments stay as
     * they are.
     */
    @Transactional
    fun markNoShow(loaded: Appointment, notify: Boolean, now: Instant = Instant.now()): Appointment {
        // Read again under the row lock: a visit completed (or marked) meanwhile is judged as it is now.
        val appointment = appts.findByIdForUpdate(loaded.id).orElse(loaded)
        if (!appointment.canMarkNoShow(now)) {
            throw CodedStatusException(
                HttpStatus.CONFLICT,
                "Only a confirmed or started visit can be marked as a no-show, from its start until 24 hours after its end",
                ErrorCode.NO_SHOW_NOT_ALLOWED,
            )
        }
        appointment.status = Appointment.Status.NO_SHOW
        val saved = appts.save(appointment)
        if (notify) {
            val lang = saved.patient.language
            val text = PatientAppointmentPushI18n.noShow(lang, PatientAppointmentPushI18n.visitDate(saved.startAt, zoneOf(saved), lang))
            notifyPatient(saved, Notification(
                patient = saved.patient,
                title = text.title,
                message = text.body,
                type = Notification.Type.APPOINTMENT_NO_SHOW,
                appointmentId = saved.id,
            ))
        }
        return saved
    }

    private fun cancelActiveAppointment(appointment: Appointment, reason: Appointment.CancelReason?, note: String?): Boolean {
        if (appointment.status == Appointment.Status.CANCELLED ||
            appointment.status == Appointment.Status.COMPLETED
        ) {
            return false
        }

        appointment.status = Appointment.Status.CANCELLED
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        if (reason != null) {
            appointment.cancelReasonCode = reason
            appointment.cancelNote = cleanNote
        }
        appts.save(appointment)

        try {
            fulfillmentService.revertFulfillmentsForCancelledAppointment(appointment.id)
        } catch (_: Exception) {
            // Cancellation must not fail if plan fulfillment rollback fails.
        }

        notifyPatient(appointment, cancelledNotification(appointment, reason, cleanNote))
        return true
    }

    /** Without a reason: the English text as before. With one: in the patient's language, with the reason. */
    private fun cancelledNotification(appointment: Appointment, reason: Appointment.CancelReason?, note: String?): Notification {
        if (reason == null) {
            val startLdt = appointment.startAt.atZone(zoneOf(appointment)).toLocalDateTime()
            val monthName = startLdt.month.name.lowercase().replaceFirstChar { it.uppercase() }
            val dateStr = "${startLdt.dayOfMonth} $monthName ${startLdt.year}"
            return Notification(
                patient = appointment.patient,
                title = "Appointment Cancelled",
                message = "Doctor has cancelled your appointment on $dateStr. Please make another appointment.",
                type = Notification.Type.APPOINTMENT_CANCELLED,
                appointmentId = appointment.id,
            )
        }
        val lang = appointment.patient.language
        val date = PatientAppointmentPushI18n.visitDate(appointment.startAt, zoneOf(appointment), lang)
        val text = PatientAppointmentPushI18n.cancelled(lang, date, reason.name)
        return Notification(
            patient = appointment.patient,
            title = text.title,
            message = text.body,
            type = Notification.Type.APPOINTMENT_CANCELLED,
            appointmentId = appointment.id,
            cancelReasonCode = reason.name,
            cancelNote = note,
        )
    }

    private fun notifyPatient(appointment: Appointment, notification: Notification) {
        val saved = notificationService.save(notification)
        // After the commit: a rolled-back status change never reaches the patient's phone.
        notificationService.sendPatientNotificationAfterCommit(appointment.patient.fcmToken, saved)
    }

    private fun zoneOf(appointment: Appointment): ZoneId = ZoneId.of(appointment.doctor.timeZone)
}
