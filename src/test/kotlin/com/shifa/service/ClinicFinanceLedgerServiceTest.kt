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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.time.Instant

class ClinicFinanceLedgerServiceTest {

    private val linesRepo = mock(TreatmentPlanLineRepository::class.java)
    private val paymentsRepo = mock(TreatmentPlanPaymentRepository::class.java)
    private val service = ClinicFinanceLedgerService(linesRepo, paymentsRepo)

    private fun stubPlanTotals(vararg planLines: Pair<Long, TreatmentPlanLine>) {
        for ((planId, line) in planLines) {
            `when`(linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(planId)).thenReturn(listOf(line))
        }
    }

    private fun stubBatchPayments(paymentsByPlan: Map<Long, List<TreatmentPlanPayment>>) {
        val allPayments = paymentsByPlan.values.flatten()
        `when`(paymentsRepo.findByPlanIdsOrderByRecordedAtAsc(anyCollection())).thenReturn(allPayments)
    }

    private fun mockPayment(
        planId: Long,
        amountMinor: Long,
        linkedAppt: Appointment? = null,
    ): TreatmentPlanPayment {
        val plan = mock(TreatmentPlan::class.java)
        `when`(plan.id).thenReturn(planId)
        val payment = mock(TreatmentPlanPayment::class.java)
        `when`(payment.plan).thenReturn(plan)
        `when`(payment.amountMinor).thenReturn(amountMinor)
        `when`(payment.linkedAppointment).thenReturn(linkedAppt)
        return payment
    }

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
            linesRepo.findAllLinesForClinicLedger(1L),
        ).thenReturn(listOf(lineMay, lineJune))
        stubPlanTotals(1L to lineMay, 2L to lineJune)
        stubBatchPayments(mapOf(1L to emptyList(), 2L to emptyList()))

        val earnings = service.doctorEarnings(1L, null, null, null)
        assertEquals(1, earnings.size)
        assertEquals(16L, earnings[0].doctorProfileId)
        assertEquals(2, earnings[0].visitCount)
        assertEquals(965_400L, earnings[0].grossMinor)
        verify(paymentsRepo, times(1)).findByPlanIdsOrderByRecordedAtAsc(anyCollection())
        verify(paymentsRepo, never()).findByPlan_IdOrderByRecordedAtAsc(1L)
    }

    @Test
    fun `doctor earnings month filter limits by appointment startAt`() {
        val may = Instant.parse("2026-05-30T10:00:00Z")
        val june = Instant.parse("2026-06-01T10:00:00Z")
        val monthStart = Instant.parse("2026-06-01T00:00:00Z")
        val monthEnd = Instant.parse("2026-07-01T00:00:00Z")
        val lineMay = mockLine(101L, 1L, 16L, 202_400L, may)
        val lineJune = mockLine(102L, 2L, 16L, 763_000L, june)
        `when`(linesRepo.findLinesForClinicLedgerInRange(1L, monthStart, monthEnd))
            .thenReturn(listOf(lineJune))
        stubPlanTotals(2L to lineJune)
        stubBatchPayments(mapOf(2L to emptyList()))

        val earnings = service.doctorEarnings(1L, monthStart, monthEnd, null)
        assertEquals(1, earnings[0].visitCount)
        assertEquals(763_000L, earnings[0].grossMinor)

        val visits = service.visitFinanceTotals(1L, monthStart, monthEnd, null)
        assertEquals(1, visits.size)
        assertEquals(102L, visits[0].appointmentId)
        assertEquals(763_000L, visits.sumOf { it.grossMinor })
    }

    @Test
    fun `doctor earnings reconcile with visit finance totals`() {
        val may = Instant.parse("2026-05-30T10:00:00Z")
        val june = Instant.parse("2026-06-01T10:00:00Z")
        val lineMay = mockLine(101L, 1L, 16L, 202_400L, may)
        val lineJune = mockLine(102L, 2L, 16L, 763_000L, june)
        val juneAppt = lineJune.linkedAppointment!!
        val paid = mockPayment(2L, 763_000L, juneAppt)
        `when`(
            linesRepo.findAllLinesForClinicLedger(1L),
        ).thenReturn(listOf(lineMay, lineJune))
        stubPlanTotals(1L to lineMay, 2L to lineJune)
        stubBatchPayments(mapOf(1L to emptyList(), 2L to listOf(paid)))

        assertTrue(service.doctorEarningsReconcileWithVisitTotals(1L, null, null, null))
        val visits = service.visitFinanceTotals(1L, null, null, null)
        assertEquals(965_400L, visits.sumOf { it.grossMinor })
        assertEquals(763_000L, visits.sumOf { it.collectedMinor })
        val earnings = service.doctorEarnings(1L, null, null, null)
        assertEquals(965_400L, earnings.sumOf { it.grossMinor })
        assertEquals(763_000L, earnings.sumOf { it.collectedMinor })
    }

    @Test
    fun `month scoped dashboard metrics aggregate visit totals`() {
        val june = Instant.parse("2026-06-01T10:00:00Z")
        val monthStart = Instant.parse("2026-06-01T00:00:00Z")
        val monthEnd = Instant.parse("2026-07-01T00:00:00Z")
        val lineJune = mockLine(102L, 2L, 16L, 763_000L, june)
        val juneAppt = lineJune.linkedAppointment!!
        val paid = mockPayment(2L, 500_000L, juneAppt)
        `when`(linesRepo.findLinesForClinicLedgerInRange(1L, monthStart, monthEnd))
            .thenReturn(listOf(lineJune))
        stubPlanTotals(2L to lineJune)
        stubBatchPayments(mapOf(2L to listOf(paid)))

        val metrics = service.monthScopedDashboardMetrics(1L, monthStart, monthEnd, null)
        assertEquals(500_000L, metrics.totalRevenueMinor)
        assertEquals(763_000L, metrics.totalExpectedMinor)
        assertEquals(263_000L, metrics.outstandingMinor)
    }

    @Test
    fun `visit ledger finance matches visit finance totals for same lines`() {
        val june = Instant.parse("2026-06-01T10:00:00Z")
        val monthStart = Instant.parse("2026-06-01T00:00:00Z")
        val monthEnd = Instant.parse("2026-07-01T00:00:00Z")
        val lineJune = mockLine(102L, 2L, 16L, 763_000L, june)
        val juneAppt = lineJune.linkedAppointment!!
        val paid = mockPayment(2L, 763_000L, juneAppt)
        stubPlanTotals(2L to lineJune)
        `when`(linesRepo.findLinesForClinicLedgerInRange(1L, monthStart, monthEnd))
            .thenReturn(listOf(lineJune))
        stubBatchPayments(mapOf(2L to listOf(paid)))

        val ledgerFinance = service.visitLedgerFinance(102L, listOf(lineJune), mapOf(2L to listOf(paid)))
        val visitTotal = service.visitFinanceTotals(1L, monthStart, monthEnd, null).single()

        assertEquals(visitTotal.grossMinor, ledgerFinance.visitTotalMinor)
        assertEquals(visitTotal.collectedMinor, ledgerFinance.visitCollectedMinor)
        assertEquals("PAID", ledgerFinance.paymentStatus)
    }

    @Test
    fun `applySplits leaves rows without configured percent unchanged`() {
        val earnings = listOf(
            ClinicFinanceLedgerService.DoctorEarningAgg(
                doctorProfileId = 16L,
                visitCount = 1,
                grossMinor = 763_000L,
                collectedMinor = 500_000L,
                outstandingMinor = 263_000L,
            ),
        )
        val revenueShareService = DoctorRevenueShareService(
            mock(com.shifa.repo.ClinicRepository::class.java),
            mock(com.shifa.repo.ClinicMembershipRepository::class.java),
            mock(com.shifa.repo.DoctorProfileRepository::class.java),
        )
        val out = revenueShareService.applySplits(earnings, emptyMap())
        assertNull(out[0].revenueSharePercent)
        assertNull(out[0].doctorShareGrossMinor)
    }

    @Test
    fun `applySplits reconciles gross and collected shares with totals`() {
        val earnings = listOf(
            ClinicFinanceLedgerService.DoctorEarningAgg(
                doctorProfileId = 16L,
                visitCount = 1,
                grossMinor = 1_000L,
                collectedMinor = 750L,
                outstandingMinor = 250L,
            ),
        )
        val revenueShareService = DoctorRevenueShareService(
            mock(com.shifa.repo.ClinicRepository::class.java),
            mock(com.shifa.repo.ClinicMembershipRepository::class.java),
            mock(com.shifa.repo.DoctorProfileRepository::class.java),
        )
        val split = revenueShareService.applySplits(earnings, mapOf(16L to 40)).single()
        assertEquals(400L, split.doctorShareGrossMinor)
        assertEquals(600L, split.clinicShareGrossMinor)
        assertEquals(300L, split.doctorShareCollectedMinor)
        assertEquals(450L, split.clinicShareCollectedMinor)
    }
}
