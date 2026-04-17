package com.shifa.service

import com.shifa.domain.Notification
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.NotificationRepository
import com.shifa.repo.TaskCheckInRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Task reminders: schedule is in patient's timezone; reminder window is in patient's "now".
 * Appointment reminders: UTC.
 */
@Service
class ReminderNotificationService(
    private val taskCheckInRepository: TaskCheckInRepository,
    private val notificationRepository: NotificationRepository,
    private val appointmentRepository: AppointmentRepository,
    private val doctorProfileRepository: DoctorProfileRepository,
    private val fcmService: FcmService,
) {
    private val log = LoggerFactory.getLogger(ReminderNotificationService::class.java)

    /** Task reminder window: 4–6 minutes from now in patient's timezone (fire at ~5 min before scheduled time). */
    private val taskReminderMinutesFrom = 4
    private val taskReminderMinutesTo = 6

    /** Appointment reminder window: 55–65 minutes from now (fire at ~1 hour before). */
    private val appointmentReminderMinutesFrom = 55
    private val appointmentReminderMinutesTo = 65

    @Transactional
    fun sendTaskReminders() {
        val allPending = taskCheckInRepository.findAllPendingForReminderWithTaskAndPatient()
        for (checkIn in allPending) {
            val task = checkIn.task
            val patient = task.patient
            val patientTz = patient.timeZone?.takeIf { it.isNotBlank() } ?: "UTC"
            val zone = ZoneId.of(patientTz)
            val now = ZonedDateTime.now(zone)
            val date = now.toLocalDate()
            val timeFrom = now.toLocalTime().plusMinutes(taskReminderMinutesFrom.toLong()).truncatedTo(ChronoUnit.MINUTES)
            val timeTo = now.toLocalTime().plusMinutes(taskReminderMinutesTo.toLong()).truncatedTo(ChronoUnit.MINUTES)
            if (checkIn.scheduledDate != date) continue
            val st = checkIn.scheduledTime ?: continue
            if (st.isBefore(timeFrom) || st.isAfter(timeTo)) continue
            try {
                val title = "Task reminder"
                val message = "${task.taskName} is scheduled at ${checkIn.scheduledTime}. Please complete when ready."
                val notification = Notification(
                    patient = patient,
                    doctor = null,
                    title = title,
                    message = message,
                    type = Notification.Type.TASK_REMINDER,
                    appointmentId = null,
                    documentAccessRequestId = null,
                    taskId = task.id,
                )
                val saved = notificationRepository.save(notification)
                patient.fcmToken?.let { token ->
                    fcmService.sendPatientNotification(token, saved)
                }
                checkIn.reminderSentAt = Instant.now()
                taskCheckInRepository.save(checkIn)
                log.info("Task reminder sent for checkIn id={} task={}", checkIn.id, task.id)
            } catch (e: Exception) {
                log.warn("Task reminder failed for checkIn id={}: {}", checkIn.id, e.message)
            }
        }
    }

    @Transactional
    fun sendAppointmentReminders() {
        val now = Instant.now()
        val windowStart = now.plus(appointmentReminderMinutesFrom.toLong(), ChronoUnit.MINUTES)
        val windowEnd = now.plus(appointmentReminderMinutesTo.toLong(), ChronoUnit.MINUTES)
        val appointments = appointmentRepository.findAppointmentsStartingBetween(windowStart, windowEnd)
        for (appointment in appointments) {
            try {
                val patient = appointment.patient
                val existing = notificationRepository.findByPatient_IdAndAppointmentIdAndType(
                    patient.id!!,
                    appointment.id,
                    Notification.Type.APPOINTMENT_REMINDER
                )
                if (existing.isNotEmpty()) continue
                val title = "Appointment reminder"
                val message = "Your appointment is in about 1 hour. Please be ready."
                val notification = Notification(
                    patient = patient,
                    doctor = appointment.doctor,
                    title = title,
                    message = message,
                    type = Notification.Type.APPOINTMENT_REMINDER,
                    appointmentId = appointment.id,
                    documentAccessRequestId = null,
                    taskId = null,
                )
                val saved = notificationRepository.save(notification)
                patient.fcmToken?.let { token ->
                    fcmService.sendPatientNotification(token, saved)
                }
                log.info("Appointment reminder sent for appointment id={} patient={}", appointment.id, patient.id)
            } catch (e: Exception) {
                log.warn("Appointment reminder failed for appointment id={}: {}", appointment.id, e.message)
            }
        }
    }
}
