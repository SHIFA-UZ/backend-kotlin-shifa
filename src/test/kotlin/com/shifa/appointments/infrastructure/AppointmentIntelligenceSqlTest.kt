package com.shifa.appointments.infrastructure

import com.shifa.appointments.domain.AppointmentFilter
import com.shifa.appointments.domain.DocumentationFilter

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class AppointmentIntelligenceSqlTest {
    private val filter = AppointmentFilter(
        startInclusive = Instant.parse("2026-10-01T00:00:00Z"),
        endExclusive = Instant.parse("2026-11-01T00:00:00Z"),
        zoneId = "Asia/Tashkent",
        now = Instant.parse("2026-10-16T10:00:00Z"),
        statuses = setOf("COMPLETED"),
        patientId = 9L,
        documentation = DocumentationFilter.NO_CONSULTATION_NOTE,
    )

    @Test
    fun `count sql does not join payments notes or patients`() {
        val sql = AppointmentIntelligenceSql.count(5L, filter).sql
        assertFalse(sql.contains("JOIN", ignoreCase = true))
        assertTrue(sql.contains("a.doctor_id = :doctorId"))
        assertTrue(sql.contains("a.start_at >= :startInclusive"))
        assertTrue(sql.contains("a.start_at < :endExclusive"))
        assertTrue(sql.contains("NOT EXISTS"))
        assertFalse(sql.contains("payments"))
    }

    @Test
    fun `ledger collected sql excludes waived amounts and gateway payment rows`() {
        val sql = AppointmentIntelligenceSql.ledgerCollected(
            5L,
            filter.startInclusive,
            filter.endExclusive,
        ).sql
        assertTrue(sql.contains("treatment_plan_payments"))
        assertTrue(sql.contains("WAIVED"))
        assertTrue(sql.contains("attending_doctor_id"))
        assertFalse(sql.contains("FROM payments"))
        assertFalse(sql.contains("financial_records"))
    }

    @Test
    fun `consultation collected sql is paid consultation checkouts only`() {
        val sql = AppointmentIntelligenceSql.consultationCollected(
            5L,
            filter.startInclusive,
            filter.endExclusive,
        ).sql
        assertTrue(sql.contains("kind = 'CONSULTATION'"))
        assertTrue(sql.contains("status = 'PAID'"))
        assertTrue(sql.contains("payee_doctor_id"))
        assertFalse(sql.contains("TREATMENT_PLAN"))
        assertFalse(sql.contains("treatment_plan_payments"))
    }
}
