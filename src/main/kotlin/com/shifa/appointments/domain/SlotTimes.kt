package com.shifa.appointments.domain

import java.time.LocalTime

/**
 * The end of the slot that starts at [start] and lasts [minutes], or null when it would end after [end] or pass
 * midnight. LocalTime wraps at 24:00 (23:30 + 30 min is 00:00, which is "before" 23:59), so a loop that only compared
 * with [end] never stopped for hours ending near midnight: one request spun a CPU and held its database connection
 * until the pool ran out (production, 2026-11-11).
 */
fun slotEnd(start: LocalTime, minutes: Long, end: LocalTime): LocalTime? =
    start.plusMinutes(minutes).takeIf { it > start && it <= end }
