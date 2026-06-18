package com.shifa.service

import com.shifa.domain.Notification
import com.shifa.domain.TreatmentPlan
import com.shifa.domain.TreatmentPlanLine
import com.shifa.repo.NotificationRepository
import com.shifa.repo.TreatmentPlanLineRepository
import com.shifa.repo.TreatmentPlanRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Centralises the "lifecycle" rules for [TreatmentPlan.status].
 *
 * Today the only automatic transition is **ACTIVE → COMPLETED** once every
 * non-cancelled plan line is in [TreatmentPlanLine.LineStatus.COMPLETED].
 * Everything else (DRAFT, ON_HOLD, IN_PROGRESS, CANCELLED) stays under manual
 * control via the dedicated `PATCH /api/treatment-plans/{planId}/status`
 * endpoint so that:
 *
 *   * Plans with any open line (PLANNED / SCHEDULED / IN_PROGRESS) stay
 *     ACTIVE — completing a visit or its appointment does not close the plan.
 *   * Doctors retain full manual control over edge cases (e.g. plans without
 *     any lines, or plans they want to forcibly mark complete early).
 *
 * Auto-completion intentionally:
 *   * **Skips plans already CANCELLED**: a cancelled plan is terminal and
 *     must not be silently reopened.
 *   * **Skips plans with no active lines**: there is no completion signal in
 *     that case; if the doctor wants such a plan closed they transition it
 *     manually.
 *   * **Is best-effort**: any failure is swallowed so it never blocks the
 *     appointment-complete call that triggered it.
 */
@Service
class TreatmentPlanStatusService(
    private val plans: TreatmentPlanRepository,
    private val lines: TreatmentPlanLineRepository,
    private val notifications: NotificationRepository,
) {

    /**
     * Re-evaluates every treatment plan that has a line linked to
     * [appointmentId] and promotes any plan whose non-cancelled lines are
     * all [TreatmentPlanLine.LineStatus.COMPLETED]. Designed to be called
     * from the appointment-complete flow (and after line fulfillment).
     *
     * Runs in its own [Propagation.REQUIRES_NEW] transaction and swallows
     * its exceptions: under no circumstance should plan-status bookkeeping
     * block (or roll back) the caller's appointment-completion transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun maybeAutoCompletePlansForAppointment(appointmentId: Long) {
        try {
            val touched = lines.findByLinkedAppointment_Id(appointmentId)
                .mapNotNull { it.plan.id }
                .toSet()
            for (planId in touched) {
                try {
                    maybeAutoCompletePlan(planId)
                } catch (_: Exception) {
                    // Per-plan failure must not stop the others.
                }
            }
        } catch (_: Exception) {
            // Best-effort by design.
        }
    }

    /**
     * Promotes [planId] to [TreatmentPlan.Status.COMPLETED] **iff** every
     * non-cancelled line on the plan is COMPLETED. No-ops otherwise.
     *
     * Visible for direct unit-testing; production code should generally go
     * through [maybeAutoCompletePlansForAppointment] which iterates from an
     * appointment id.
     */
    @Transactional
    fun maybeAutoCompletePlan(planId: Long) {
        val plan = plans.findById(planId).orElse(null) ?: return

        // Terminal states we never auto-transition out of.
        if (plan.status == TreatmentPlan.Status.COMPLETED) return
        if (plan.status == TreatmentPlan.Status.CANCELLED) return

        val allLines = lines.findByPlan_IdOrderBySortOrderAscIdAsc(planId)
        val activeLines = allLines.filter { it.status != TreatmentPlanLine.LineStatus.CANCELLED }

        // Plans with zero active lines have no completion signal — we can't
        // infer the doctor's intent, so we leave the status alone.
        if (activeLines.isEmpty()) return

        val allLinesCompleted = activeLines.all {
            it.status == TreatmentPlanLine.LineStatus.COMPLETED
        }
        if (!allLinesCompleted) return

        plan.status = TreatmentPlan.Status.COMPLETED
        plan.updatedAt = OffsetDateTime.now()
        plans.save(plan)

        // Best-effort patient notification. Failure here must not roll back
        // the status transition (which is the actual user-visible outcome).
        try {
            notifications.save(
                Notification(
                    patient = plan.patient,
                    title = "Treatment plan completed",
                    message = "All planned treatments on \"${plan.title ?: "your treatment plan"}\" have been completed.",
                    type = Notification.Type.TREATMENT_PLAN_UPDATED,
                )
            )
        } catch (_: Exception) {
            // Don't fail the transition because of notification persistence.
        }
    }
}
