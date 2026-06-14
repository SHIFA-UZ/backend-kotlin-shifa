package com.shifa.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ReminderNotificationScheduler(
    private val reminderNotificationService: ReminderNotificationService,
) {
    private val log = LoggerFactory.getLogger(ReminderNotificationScheduler::class.java)

    /**
     * Run every minute: task, appointment, and pending payment (video) reminders.
     * fixedDelay (not fixedRate) waits 60s after the previous run finishes so slow steps
     * cannot pile up back-to-back and saturate CPU.
     */
    @Scheduled(fixedDelay = 60_000)
    fun runReminders() {
        val runStart = System.nanoTime()
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
        val totalMs = (System.nanoTime() - runStart) / 1_000_000
        if (totalMs > 5_000) {
            log.warn("Reminder scheduler run took {}ms (investigate slow steps above)", totalMs)
        } else if (totalMs > 1_000) {
            log.info("Reminder scheduler run took {}ms", totalMs)
        }
    }

    private fun runStep(name: String, block: () -> Unit) {
        val stepStart = System.nanoTime()
        try {
            block()
        } catch (e: Exception) {
            log.warn("Reminder scheduler step '{}' failed: {}", name, e.message, e)
        } finally {
            val stepMs = (System.nanoTime() - stepStart) / 1_000_000
            if (stepMs > 2_000) {
                log.warn("Reminder scheduler step '{}' took {}ms", name, stepMs)
            }
        }
    }
}
