package com.shifa.service

import com.shifa.domain.Clinic
import com.shifa.domain.PatientProfile
import com.shifa.domain.TreatmentPlan
import com.shifa.domain.TreatmentPlanLine
import com.shifa.repo.NotificationRepository
import com.shifa.repo.TreatmentPlanLineRepository
import com.shifa.repo.TreatmentPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional

class TreatmentPlanStatusServiceTest {

    private val plans = mock(TreatmentPlanRepository::class.java)
    private val linesRepo = mock(TreatmentPlanLineRepository::class.java)
    private val notifications = mock(NotificationRepository::class.java)
    private val service = TreatmentPlanStatusService(plans, linesRepo, notifications)

    private val clinic = Clinic(id = 1L, name = "Test Clinic")
    private val patient = PatientProfile(id = 10L, fullName = "Jane Doe")
    private val plan = TreatmentPlan(
        id = 42L,
        clinic = clinic,
        patient = patient,
        status = TreatmentPlan.Status.ACTIVE,
        title = "Full mouth rehab",
    )

    private fun line(
        id: Long,
        status: TreatmentPlanLine.LineStatus,
    ): TreatmentPlanLine {
        val line = TreatmentPlanLine(
            id = id,
            plan = plan,
            title = "Service $id",
            unitPriceMinor = 100_000L,
            status = status,
        )
        return line
    }

    @Test
    fun `does not complete plan when some lines are still open`() {
        `when`(plans.findById(42L)).thenReturn(Optional.of(plan))
        `when`(linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(42L)).thenReturn(
            listOf(
                line(1L, TreatmentPlanLine.LineStatus.COMPLETED),
                line(2L, TreatmentPlanLine.LineStatus.PLANNED),
            ),
        )

        service.maybeAutoCompletePlan(42L)

        assertEquals(TreatmentPlan.Status.ACTIVE, plan.status)
        verify(plans, never()).save(any())
        verify(notifications, never()).save(any())
    }

    @Test
    fun `completes plan when every non-cancelled line is completed`() {
        `when`(plans.findById(42L)).thenReturn(Optional.of(plan))
        `when`(linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(42L)).thenReturn(
            listOf(
                line(1L, TreatmentPlanLine.LineStatus.COMPLETED),
                line(2L, TreatmentPlanLine.LineStatus.COMPLETED),
                line(3L, TreatmentPlanLine.LineStatus.CANCELLED),
            ),
        )

        service.maybeAutoCompletePlan(42L)

        assertEquals(TreatmentPlan.Status.COMPLETED, plan.status)
        verify(plans).save(plan)
        verify(notifications).save(any())
    }

    @Test
    fun `does not complete plan with only cancelled lines`() {
        `when`(plans.findById(42L)).thenReturn(Optional.of(plan))
        `when`(linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(42L)).thenReturn(
            listOf(line(1L, TreatmentPlanLine.LineStatus.CANCELLED)),
        )

        service.maybeAutoCompletePlan(42L)

        assertEquals(TreatmentPlan.Status.ACTIVE, plan.status)
        verify(plans, never()).save(any())
    }

    @Test
    fun `does not transition cancelled plan`() {
        plan.status = TreatmentPlan.Status.CANCELLED
        `when`(plans.findById(42L)).thenReturn(Optional.of(plan))
        `when`(linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(42L)).thenReturn(
            listOf(line(1L, TreatmentPlanLine.LineStatus.COMPLETED)),
        )

        service.maybeAutoCompletePlan(42L)

        assertEquals(TreatmentPlan.Status.CANCELLED, plan.status)
        verify(plans, never()).save(any())
    }
}
