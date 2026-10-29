package com.shifa.clinics.application

import com.shifa.clinics.domain.Clinic
import com.shifa.patients.domain.PatientProfile
import com.shifa.clinics.domain.TreatmentPlan
import com.shifa.clinics.domain.TreatmentPlanLine
import com.shifa.notifications.application.NotificationService
import com.shifa.notifications.domain.Notification
import com.shifa.clinics.infrastructure.TreatmentPlanLineRepository
import com.shifa.clinics.infrastructure.TreatmentPlanRepository
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
    private val notificationService = mock(NotificationService::class.java)
    private val service = TreatmentPlanStatusService(plans, linesRepo, notificationService)

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
        verify(notificationService, never()).save(anyNotification())
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
        verify(notificationService).save(anyNotification())
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

    private fun anyNotification(): Notification =
        org.mockito.ArgumentMatchers.any(Notification::class.java)
            ?: Notification(title = "", message = "", type = Notification.Type.entries.first())
}
