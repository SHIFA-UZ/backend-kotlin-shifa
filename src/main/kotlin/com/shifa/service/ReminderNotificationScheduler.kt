package com.shifa.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ReminderNotificationScheduler(
    private val reminderNotificationService: ReminderNotificationService,
) {
    private val log = LoggerFactory.getLogger(ReminderNotificationScheduler::class.java)

    /** Run every minute: task, appointment, and pending payment (video) reminders. */
    @Scheduled(fixedRate = 60_000)
    fun runReminders() {
        runStep("task reminders") { reminderNotificationService.sendTaskReminders() }
        runStep("appointment reminders") { reminderNotificationService.sendAppointmentReminders() }
        runStep("appointment SMS reminders") { reminderNotificationService.sendAppointmentSmsReminders() }
        runStep("consultation payment reminders") {
            reminderNotificationService.sendPendingConsultationPaymentReminders()
        }
        runStep("treatment plan payment reminders") {
            reminderNotificationService.sendTreatmentPlanPaymentReminders()
        }
        runStep("installment reminders") { reminderNotificationService.sendInstallmentDueReminders() }
        runStep("prophylaxis reminders") { reminderNotificationService.sendProphylaxisReminders() }
    }

    private fun runStep(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("Reminder scheduler step '{}' failed: {}", name, e.message, e)
        }
    }
}
