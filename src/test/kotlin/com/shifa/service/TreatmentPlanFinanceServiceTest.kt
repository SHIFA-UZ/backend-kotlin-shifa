package com.shifa.service

import com.shifa.domain.TreatmentPlanLine
import com.shifa.domain.TreatmentPlanPayment
import com.shifa.repo.FinancialRecordRepository
import com.shifa.repo.TreatmentPlanLineRepository
import com.shifa.repo.TreatmentPlanPaymentRepository
import com.shifa.repo.TreatmentPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class TreatmentPlanFinanceServiceTest {

    private val plans = mock(TreatmentPlanRepository::class.java)
    private val linesRepo = mock(TreatmentPlanLineRepository::class.java)
    private val paymentsRepo = mock(TreatmentPlanPaymentRepository::class.java)
    private val financialRecords = mock(FinancialRecordRepository::class.java)
    private val service = TreatmentPlanFinanceService(plans, linesRepo, paymentsRepo, financialRecords)

    @Test
    fun `planLiveTotals sums lines and payments`() {
        val line1 = mock(TreatmentPlanLine::class.java)
        `when`(line1.unitPriceMinor).thenReturn(500_000L)
        `when`(line1.quantity).thenReturn(1)
        `when`(line1.discountMinor).thenReturn(0L)
        `when`(line1.currency).thenReturn("UZS")

        val line2 = mock(TreatmentPlanLine::class.java)
        `when`(line2.unitPriceMinor).thenReturn(263_000L)
        `when`(line2.quantity).thenReturn(1)
        `when`(line2.discountMinor).thenReturn(0L)
        `when`(line2.currency).thenReturn("UZS")

        `when`(linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(42L)).thenReturn(listOf(line1, line2))

        val payment = mock(TreatmentPlanPayment::class.java)
        `when`(payment.amountMinor).thenReturn(763_000L)
        `when`(paymentsRepo.findByPlan_IdOrderByRecordedAtAsc(42L)).thenReturn(listOf(payment))

        val totals = service.planLiveTotals(42L)

        assertEquals(763_000L, totals.estimatedMinor)
        assertEquals(763_000L, totals.paidMinor)
        assertEquals(0L, totals.remainingMinor)
        assertEquals("UZS", totals.currency)
    }

    @Test
    fun `planLiveTotals reflects partial initial payment`() {
        val line = mock(TreatmentPlanLine::class.java)
        `when`(line.unitPriceMinor).thenReturn(1_000_000L)
        `when`(line.quantity).thenReturn(1)
        `when`(line.discountMinor).thenReturn(0L)
        `when`(line.currency).thenReturn("UZS")

        `when`(linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(7L)).thenReturn(listOf(line))

        val initial = mock(TreatmentPlanPayment::class.java)
        `when`(initial.amountMinor).thenReturn(200_000L)
        `when`(paymentsRepo.findByPlan_IdOrderByRecordedAtAsc(7L)).thenReturn(listOf(initial))

        val totals = service.planLiveTotals(7L)

        assertEquals(1_000_000L, totals.estimatedMinor)
        assertEquals(200_000L, totals.paidMinor)
        assertEquals(800_000L, totals.remainingMinor)
        assertEquals("UZS", totals.currency)
    }
}
