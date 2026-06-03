package com.shifa.service

import com.shifa.domain.Appointment
import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientProfile
import com.shifa.domain.TreatmentPlan
import com.shifa.domain.TreatmentPlanLine
import com.shifa.domain.TreatmentPlanPayment
import com.shifa.repo.TreatmentPlanLineRepository
import com.shifa.repo.TreatmentPlanPaymentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.time.Instant

class ClinicFinanceLedgerServiceTest {

    private val linesRepo = mock(TreatmentPlanLineRepository::class.java)
    private val paymentsRepo = mock(TreatmentPlanPaymentRepository::class.java)
    private val service = ClinicFinanceLedgerService(linesRepo, paymentsRepo)

    private fun mockLine(
        apptId: Long,
        planId: Long,
        doctorId: Long,
        amountMinor: Long,
        startAt: Instant,
    ): TreatmentPlanLine {
        val doctor = mock(DoctorProfile::class.java)
        `when`(doctor.id).thenReturn(doctorId)
        val patient = mock(PatientProfile::class.java)
        `when`(patient.id).thenReturn(1L)
        val appt = mock(Appointment::class.java)
        `when`(appt.id).thenReturn(apptId)
        `when`(appt.doctor).thenReturn(doctor)
        `when`(appt.patient).thenReturn(patient)
        `when`(appt.startAt).thenReturn(startAt)
        val plan = mock(TreatmentPlan::class.java)
        `when`(plan.id).thenReturn(planId)
        val line = mock(TreatmentPlanLine::class.java)
        `when`(line.linkedAppointment).thenReturn(appt)
        `when`(line.plan).thenReturn(plan)
        `when`(line.assignedDoctor).thenReturn(null)
        `when`(line.unitPriceMinor).thenReturn(amountMinor)
        `when`(line.quantity).thenReturn(1)
        `when`(line.discountMinor).thenReturn(0L)
        return line
    }

    @Test
    fun `planSimplePaymentStatus maps totals to UI labels`() {
        assertEquals("NONE", service.planSimplePaymentStatus(0L, 0L))
        assertEquals("UNPAID", service.planSimplePaymentStatus(10_000L, 0L))
        assertEquals("PARTIAL", service.planSimplePaymentStatus(10_000L, 3_000L))
        assertEquals("PAID", service.planSimplePaymentStatus(10_000L, 10_000L))
    }

    @Test
    fun `visitPaymentStatus allocates plan payments proportionally`() {
        assertEquals("NONE", service.visitPaymentStatus(0L, 5_000L, 10_000L))
        assertEquals("UNPAID", service.visitPaymentStatus(4_000L, 0L, 10_000L))
        assertEquals("PARTIAL", service.visitPaymentStatus(4_000L, 5_000L, 10_000L))
        assertEquals("PAID", service.visitPaymentStatus(4_000L, 10_000L, 10_000L))
    }

    @Test
    fun `appointment explicit attributed payment applies only to that visit`() {
        val allocations = listOf(4_000L to 101L)
        assertEquals(
            4_000L,
            service.appointmentAttributedPaidMinorFromAllocations(101L, 4_000L, 8_000L, allocations),
        )
        assertEquals(
            0L,
            service.appointmentAttributedPaidMinorFromAllocations(100L, 4_000L, 8_000L, allocations),
        )
    }

    @Test
    fun `appointment pooled unallocated payment splits proportionally`() {
        val allocations = listOf<Pair<Long, Long?>>(8_000L to null)
        assertEquals(
            4_000L,
            service.appointmentAttributedPaidMinorFromAllocations(100L, 4_000L, 8_000L, allocations),
        )
        assertEquals(
            4_000L,
            service.appointmentAttributedPaidMinorFromAllocations(200L, 4_000L, 8_000L, allocations),
        )
    }

    @Test
    fun `appointmentCollectedMinor caps at visit total when plan denominator is too small`() {
        val payment = mock(TreatmentPlanPayment::class.java)
        `when`(payment.amountMinor).thenReturn(8_000L)
        `when`(payment.linkedAppointment).thenReturn(null)
        // Wrong cached plan total of 1 would allocate 8k * 4k / 1 — cap keeps visit at 4k.
        assertEquals(
            4_000L,
            service.appointmentCollectedMinor(100L, 4_000L, 1L, listOf(payment)),
        )
    }

    @Test
    fun `appointmentCollectedMinor uses live plan total for proportional split`() {
        val payment = mock(TreatmentPlanPayment::class.java)
        `when`(payment.amountMinor).thenReturn(8_000L)
        `when`(payment.linkedAppointment).thenReturn(null)
        assertEquals(
            4_000L,
            service.appointmentCollectedMinor(100L, 4_000L, 8_000L, listOf(payment)),
        )
    }

    @Test
    fun `doctor earnings all-time includes every billable visit in ledger scope`() {
        val may = Instant.parse("2026-05-30T10:00:00Z")
        val june = Instant.parse("2026-06-01T10:00:00Z")
        val lineMay = mockLine(101L, 1L, 16L, 202_400L, may)
        val lineJune = mockLine(102L, 2L, 16L, 763_000L, june)
        `when`(
            linesRepo.findLinesForClinicLedger(eq(1L), isNull(), isNull()),
        ).thenReturn(listOf(lineMay, lineJune))
        `when`(linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(1L)).thenReturn(listOf(lineMay))
        `when`(linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(2L)).thenReturn(listOf(lineJune))
        `when`(paymentsRepo.findByPlan_IdOrderByRecordedAtAsc(1L)).thenReturn(emptyList())
        `when`(paymentsRepo.findByPlan_IdOrderByRecordedAtAsc(2L)).thenReturn(emptyList())

        val earnings = service.doctorEarnings(1L, null, null, null)
        assertEquals(1, earnings.size)
        assertEquals(16L, earnings[0].doctorProfileId)
        assertEquals(2, earnings[0].visitCount)
        assertEquals(965_400L, earnings[0].grossMinor)
    }

    @Test
    fun `doctor earnings month filter limits by appointment startAt`() {
        val may = Instant.parse("2026-05-30T10:00:00Z")
        val june = Instant.parse("2026-06-01T10:00:00Z")
        val monthStart = Instant.parse("2026-06-01T00:00:00Z")
        val monthEnd = Instant.parse("2026-07-01T00:00:00Z")
        val lineMay = mockLine(101L, 1L, 16L, 202_400L, may)
        val lineJune = mockLine(102L, 2L, 16L, 763_000L, june)
        `when`(
            linesRepo.findLinesForClinicLedger(eq(1L), eq(monthStart), eq(monthEnd)),
        ).thenReturn(listOf(lineJune))
        `when`(linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(2L)).thenReturn(listOf(lineJune))
        `when`(paymentsRepo.findByPlan_IdOrderByRecordedAtAsc(2L)).thenReturn(emptyList())

        val earnings = service.doctorEarnings(1L, monthStart, monthEnd, null)
        assertEquals(1, earnings[0].visitCount)
        assertEquals(763_000L, earnings[0].grossMinor)
    }

    @Test
    fun `doctor earnings reconcile with visit finance totals`() {
        val may = Instant.parse("2026-05-30T10:00:00Z")
        val june = Instant.parse("2026-06-01T10:00:00Z")
        val lineMay = mockLine(101L, 1L, 16L, 202_400L, may)
        val lineJune = mockLine(102L, 2L, 16L, 763_000L, june)
        val juneAppt = lineJune.linkedAppointment!!
        val paid = mock(TreatmentPlanPayment::class.java)
        `when`(paid.amountMinor).thenReturn(763_000L)
        `when`(paid.linkedAppointment).thenReturn(juneAppt)
        `when`(
            linesRepo.findLinesForClinicLedger(eq(1L), isNull(), isNull()),
        ).thenReturn(listOf(lineMay, lineJune))
        `when`(linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(1L)).thenReturn(listOf(lineMay))
        `when`(linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(2L)).thenReturn(listOf(lineJune))
        `when`(paymentsRepo.findByPlan_IdOrderByRecordedAtAsc(1L)).thenReturn(emptyList())
        `when`(paymentsRepo.findByPlan_IdOrderByRecordedAtAsc(2L)).thenReturn(listOf(paid))

        assertTrue(service.doctorEarningsReconcileWithVisitTotals(1L, null, null, null))
        val visits = service.visitFinanceTotals(1L, null, null, null)
        assertEquals(965_400L, visits.sumOf { it.grossMinor })
        assertEquals(763_000L, visits.sumOf { it.collectedMinor })
    }
}
