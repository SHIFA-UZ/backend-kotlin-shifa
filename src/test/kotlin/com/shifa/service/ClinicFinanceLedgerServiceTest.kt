package com.shifa.service

import com.shifa.repo.TreatmentPlanLineRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class ClinicFinanceLedgerServiceTest {

    private val linesRepo = mock(TreatmentPlanLineRepository::class.java)
    private val service = ClinicFinanceLedgerService(linesRepo)

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
        // half of plan paid -> half of visit "covered"
        assertEquals("PARTIAL", service.visitPaymentStatus(4_000L, 5_000L, 10_000L))
        assertEquals("PAID", service.visitPaymentStatus(4_000L, 10_000L, 10_000L))
    }
}
