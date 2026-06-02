package com.shifa.service

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * DevSMS reminders fire once while the appointment is still in the future and its start time
 * is before [reminderHorizonEnd] (now + hoursBefore + tolerance).
 *
 * Used for both 24-hour and 1-hour patient settings ([PatientProfile.smsReminderHoursBefore]).
 * Includes the ideal ~N-hour window and catch-up if the scheduler missed it or SMS was enabled late.
 */
object SmsReminderTiming {

    fun reminderHorizonEnd(
        now: Instant,
        hoursBefore: Long,
        toleranceMinutes: Long,
    ): Instant =
        now.plus(hoursBefore, ChronoUnit.HOURS).plus(toleranceMinutes, ChronoUnit.MINUTES)

    fun isDue(
        now: Instant,
        appointmentStart: Instant,
        hoursBefore: Long,
        toleranceMinutes: Long,
    ): Boolean {
        if (!appointmentStart.isAfter(now)) return false
        return appointmentStart.isBefore(reminderHorizonEnd(now, hoursBefore, toleranceMinutes))
    }
}
