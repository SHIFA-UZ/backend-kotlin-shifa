package com.shifa.service

import com.shifa.domain.TreatmentPlanPayment
import com.shifa.repo.TreatmentPlanLineRepository
import com.shifa.repo.TreatmentPlanPaymentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ClinicFinanceLedgerServiceTest {

    private val linesRepo = mock(TreatmentPlanLineRepository::class.java)
    private val paymentsRepo = mock(TreatmentPlanPaymentRepository::class.java)
    private val service = ClinicFinanceLedgerService(linesRepo, paymentsRepo)

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
}
