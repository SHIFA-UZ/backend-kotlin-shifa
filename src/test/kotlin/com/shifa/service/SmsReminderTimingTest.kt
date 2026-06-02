package com.shifa.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class SmsReminderTimingTest {

    private val toleranceMinutes = 10L

    @Test
    fun `not due when appointment is more than 24h plus tolerance away`() {
        val now = Instant.parse("2026-06-01T10:00:00Z")
        val appointment = now.plus(25, ChronoUnit.HOURS)
        assertFalse(SmsReminderTiming.isDue(now, appointment, 24, toleranceMinutes))
    }

    @Test
    fun `due in ideal 24h window`() {
        val now = Instant.parse("2026-06-01T10:00:00Z")
        val appointment = now.plus(24, ChronoUnit.HOURS)
        assertTrue(SmsReminderTiming.isDue(now, appointment, 24, toleranceMinutes))
    }

    @Test
    fun `due on catch-up after ideal window was missed`() {
        val appointment = Instant.parse("2026-06-02T10:00:00Z")
        val now = appointment.minus(22, ChronoUnit.HOURS)
        assertTrue(SmsReminderTiming.isDue(now, appointment, 24, toleranceMinutes))
    }

    @Test
    fun `not due too early when SMS enabled 30h before appointment`() {
        val appointment = Instant.parse("2026-06-02T10:00:00Z")
        val now = appointment.minus(30, ChronoUnit.HOURS)
        assertFalse(SmsReminderTiming.isDue(now, appointment, 24, toleranceMinutes))
    }

    @Test
    fun `due when enabled late inside last 24h before appointment`() {
        val appointment = Instant.parse("2026-06-02T10:00:00Z")
        val now = appointment.minus(20, ChronoUnit.HOURS)
        assertTrue(SmsReminderTiming.isDue(now, appointment, 24, toleranceMinutes))
    }

    @Test
    fun `not due after appointment started`() {
        val now = Instant.parse("2026-06-02T11:00:00Z")
        val appointment = Instant.parse("2026-06-02T10:00:00Z")
        assertFalse(SmsReminderTiming.isDue(now, appointment, 24, toleranceMinutes))
    }

    // --- 1-hour SMS (same rules, hoursBefore = 1) ---

    @Test
    fun `1h not due when appointment is more than 1h plus tolerance away`() {
        val now = Instant.parse("2026-06-01T10:00:00Z")
        val appointment = now.plus(90, ChronoUnit.MINUTES)
        assertFalse(SmsReminderTiming.isDue(now, appointment, 1, toleranceMinutes))
    }

    @Test
    fun `1h due in ideal window`() {
        val now = Instant.parse("2026-06-01T10:00:00Z")
        val appointment = now.plus(1, ChronoUnit.HOURS)
        assertTrue(SmsReminderTiming.isDue(now, appointment, 1, toleranceMinutes))
    }

    @Test
    fun `1h due on catch-up after ideal window was missed`() {
        val appointment = Instant.parse("2026-06-02T10:00:00Z")
        val now = appointment.minus(50, ChronoUnit.MINUTES)
        assertTrue(SmsReminderTiming.isDue(now, appointment, 1, toleranceMinutes))
    }

    @Test
    fun `1h not due too early when enabled 90 minutes before appointment`() {
        val appointment = Instant.parse("2026-06-02T10:00:00Z")
        val now = appointment.minus(90, ChronoUnit.MINUTES)
        assertFalse(SmsReminderTiming.isDue(now, appointment, 1, toleranceMinutes))
    }

    @Test
    fun `1h due when enabled late inside last hour before appointment`() {
        val appointment = Instant.parse("2026-06-02T10:00:00Z")
        val now = appointment.minus(45, ChronoUnit.MINUTES)
        assertTrue(SmsReminderTiming.isDue(now, appointment, 1, toleranceMinutes))
    }
}
