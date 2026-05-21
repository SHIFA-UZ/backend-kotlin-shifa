package com.shifa.service

import com.shifa.domain.Appointment
import com.shifa.domain.Notification
import com.shifa.domain.TreatmentPlan
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
 * appointment linked to a plan is in [Appointment.Status.COMPLETED]. Everything
 * else (DRAFT, ON_HOLD, IN_PROGRESS, CANCELLED) stays under manual control via
 * the dedicated `PATCH /api/treatment-plans/{planId}/status` endpoint so that:
 *
 *   * Plans whose appointments are partially completed (or have been
 *     cancelled / missed) stay ACTIVE — matching the product rule that "any
 *     non-completed appointment keeps the plan open".
 *   * Doctors retain full manual control over edge cases (e.g. plans without
 *     any linked appointments, or plans they want to forcibly mark complete
 *     before the last visit).
 *
 * Auto-completion intentionally:
 *   * **Skips plans already CANCELLED**: a cancelled plan is terminal and
 *     must not be silently reopened.
 *   * **Skips plans with no linked appointments**: there is no completion
 *     signal in that case; if the doctor wants such a plan closed they
 *     transition it manually.
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
     * [appointmentId] and promotes any plan whose linked appointments are
     * all completed to [TreatmentPlan.Status.COMPLETED]. Designed to be
     * called from the appointment-complete flow.
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
     * linked appointment on the plan is COMPLETED. No-ops otherwise.
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
        // De-dupe by appointment id; the same appointment can back several
        // lines (per-service rows for the same visit) and we only care about
        // its terminal status once.
        val linkedAppointments = allLines.mapNotNull { it.linkedAppointment }
            .associateBy { it.id }
            .values

        // Plans with zero linked appointments have no completion signal —
        // we can't infer the doctor's intent, so we leave the status alone.
        if (linkedAppointments.isEmpty()) return

        val allCompleted = linkedAppointments.all {
            it.status == Appointment.Status.COMPLETED
        }
        if (!allCompleted) return

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
                    message = "All scheduled appointments on \"${plan.title ?: "your treatment plan"}\" have been completed.",
                    type = Notification.Type.TREATMENT_PLAN_UPDATED,
                )
            )
        } catch (_: Exception) {
            // Don't fail the transition because of notification persistence.
        }
    }
}
