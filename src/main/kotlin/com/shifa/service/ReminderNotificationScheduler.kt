package com.shifa.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ReminderNotificationScheduler(
    private val reminderNotificationService: ReminderNotificationService,
) {
    private val log = LoggerFactory.getLogger(ReminderNotificationScheduler::class.java)

    /** Run every minute: task reminders (5 min before) and appointment reminders (1 hour before). */
    @Scheduled(fixedRate = 60_000)
    fun runReminders() {
        try {
            reminderNotificationService.sendTaskReminders()
            reminderNotificationService.sendAppointmentReminders()
        } catch (e: Exception) {
            log.warn("Reminder scheduler failed: {}", e.message)
        }
    }
}
