package com.shifa.service

import com.shifa.domain.TreatmentPlanLine
import com.shifa.domain.TreatmentPlanPayment
import com.shifa.repo.TreatmentPlanLineRepository
import com.shifa.repo.TreatmentPlanPaymentRepository
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ClinicFinanceLedgerService(
    private val linesRepo: TreatmentPlanLineRepository,
    private val paymentsRepo: TreatmentPlanPaymentRepository,
) {

    fun lineTotal(line: TreatmentPlanLine): Long =
        (line.unitPriceMinor * line.quantity - line.discountMinor).coerceAtLeast(0)

    /** Sum of all line totals on a plan — used as the denominator for payment allocation. */
    fun planTotalMinor(planId: Long): Long =
        linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(planId)
            .sumOf { lineTotal(it) }
            .coerceAtLeast(0L)

    /** Resolve which doctor earns credit for a charge line. */
    fun earningDoctorId(line: TreatmentPlanLine): Long? =
        line.assignedDoctor?.id ?: line.linkedAppointment?.doctor?.id

    /** UI label: UNPAID | PARTIAL | PAID | NONE — proportional allocation of plan payments across visit lines. */
    fun visitPaymentStatus(visitTotal: Long, planPaid: Long, planTotal: Long): String {
        if (visitTotal <= 0L) return "NONE"
        val denom = planTotal.coerceAtLeast(1L)
        val allocated = (planPaid * visitTotal) / denom
        return when {
            allocated >= visitTotal -> "PAID"
            allocated <= 0L -> "UNPAID"
            else -> "PARTIAL"
        }
    }

    fun planSimplePaymentStatus(planTotal: Long, planPaid: Long): String {
        if (planTotal <= 0L) return "NONE"
        return when {
            planPaid <= 0L -> "UNPAID"
            planPaid >= planTotal -> "PAID"
            else -> "PARTIAL"
        }
    }

    /**
     * Payments explicitly linked to [appointmentId] count only for that visit.
     * Payments with no appointment link remain in a pool and split across visits proportionally by [visitTotal] / plan total
     * (legacy / wizard "plan-wide" payments).
     */
    fun appointmentAttributedPaidMinorFromAllocations(
        appointmentId: Long,
        visitTotal: Long,
        planTotalMinor: Long,
        allocations: List<Pair<Long, Long?>>,
    ): Long {
        var explicit = 0L
        var unallocated = 0L
        for ((amount, apptId) in allocations) {
            when {
                apptId == appointmentId -> explicit += amount
                apptId == null -> unallocated += amount
            }
        }
        val denom = planTotalMinor.coerceAtLeast(1L)
        return explicit + (unallocated * visitTotal) / denom
    }

    /** Per-visit collected amount, capped at [visitTotal]. */
    fun appointmentCollectedMinor(
        appointmentId: Long,
        visitTotal: Long,
        planTotalMinor: Long,
        payments: List<TreatmentPlanPayment>,
    ): Long {
        if (visitTotal <= 0L) return 0L
        val raw = appointmentAttributedPaidMinorFromPayments(
            appointmentId,
            visitTotal,
            planTotalMinor,
            payments,
        )
        return raw.coerceAtMost(visitTotal)
    }

    fun appointmentAttributedPaidMinorFromPayments(
        appointmentId: Long,
        visitTotal: Long,
        planTotalMinor: Long,
        payments: List<TreatmentPlanPayment>,
    ): Long {
        return appointmentAttributedPaidMinorFromAllocations(
            appointmentId,
            visitTotal,
            planTotalMinor,
            payments.map { p ->
                val aid = runCatching { p.linkedAppointment?.id }.getOrNull()
                p.amountMinor to aid
            },
        )
    }

    /** Loads ledger lines; omit both dates for all-time (matches appointment ledger default scope). */
    private fun loadLedgerLines(
        clinicId: Long,
        from: Instant?,
        toExclusive: Instant?,
    ): List<TreatmentPlanLine> {
        require((from == null) == (toExclusive == null)) {
            "from and toExclusive must both be set or both omitted"
        }
        return if (from == null) {
            linesRepo.findAllLinesForClinicLedger(clinicId)
        } else {
            linesRepo.findLinesForClinicLedgerInRange(clinicId, from, toExclusive!!)
        }
    }

    /**
     * Per-visit gross/collected totals using the same allocation rules as the appointment ledger.
     * Optional [from] / [toExclusive] filter on appointment.startAt (visit date).
     */
    fun visitFinanceTotals(
        clinicId: Long,
        from: Instant?,
        toExclusive: Instant?,
        patientFilter: Set<Long>?,
    ): List<VisitFinanceTotal> {
        val lines = loadLedgerLines(clinicId, from, toExclusive)
        val filtered =
            if (patientFilter == null) lines
            else lines.filter { line ->
                val pid = line.linkedAppointment?.patient?.id ?: return@filter false
                pid in patientFilter
            }
        if (filtered.isEmpty()) return emptyList()

        val planTotalCache = mutableMapOf<Long, Long>()
        return filtered.groupBy { it.linkedAppointment!!.id }.map { (apptId, apptLines) ->
            val appt = apptLines.first().linkedAppointment!!
            val plan = apptLines.first().plan
            val planCap = planTotalCache.getOrPut(plan.id) { planTotalMinor(plan.id) }.coerceAtLeast(1L)
            val visitTotal = apptLines.sumOf { lineTotal(it) }
            val payments = paymentsRepo.findByPlan_IdOrderByRecordedAtAsc(plan.id)
            val visitCollected = appointmentCollectedMinor(apptId, visitTotal, planCap, payments)
            VisitFinanceTotal(
                appointmentId = apptId,
                grossMinor = visitTotal,
                collectedMinor = visitCollected,
                lines = apptLines,
            )
        }
    }

    /**
     * Aggregates doctor earnings from linked appointment charges.
     *
     * Scope matches Finance → By appointment: non-cancelled visits with treatment-plan
     * lines linked to an appointment. [from] / [toExclusive] filter on **appointment.startAt**
     * (visit date); when both are null, all qualifying visits for the clinic are included.
     */
    fun doctorEarnings(
        clinicId: Long,
        from: Instant?,
        toExclusive: Instant?,
        patientFilter: Set<Long>?,
    ): List<DoctorEarningAgg> {
        val visits = visitFinanceTotals(clinicId, from, toExclusive, patientFilter)
        if (visits.isEmpty()) return emptyList()

        data class MutableAgg(var grossMinor: Long, var collectedMinor: Long, var visits: MutableSet<Long>)

        val byDoctor = mutableMapOf<Long, MutableAgg>()
        for (visit in visits) {
            val appt = visit.lines.first().linkedAppointment!!
            val patientId = appt.patient.id
            if (patientFilter != null && patientId != null && patientId !in patientFilter) continue
            val visitTotal = visit.grossMinor
            val visitCollected = visit.collectedMinor
            for (line in visit.lines) {
                val doctorId = earningDoctorId(line) ?: continue
                val lineAmount = lineTotal(line)
                val lineCollected =
                    if (visitTotal > 0L) (visitCollected * lineAmount) / visitTotal else 0L
                val agg = byDoctor.getOrPut(doctorId) { MutableAgg(0L, 0L, mutableSetOf()) }
                agg.grossMinor += lineAmount
                agg.collectedMinor += lineCollected.coerceAtMost(lineAmount)
                agg.visits.add(appt.id)
            }
        }
        return byDoctor.map { (doctorId, agg) ->
            DoctorEarningAgg(
                doctorProfileId = doctorId,
                visitCount = agg.visits.size,
                grossMinor = agg.grossMinor,
                collectedMinor = agg.collectedMinor,
                outstandingMinor = (agg.grossMinor - agg.collectedMinor).coerceAtLeast(0),
            )
        }.sortedByDescending { it.grossMinor }
    }

    /** Validates that per-doctor totals reconcile with per-visit totals for the same scope. */
    fun doctorEarningsReconcileWithVisitTotals(
        clinicId: Long,
        from: Instant?,
        toExclusive: Instant?,
        patientFilter: Set<Long>?,
    ): Boolean {
        val visits = visitFinanceTotals(clinicId, from, toExclusive, patientFilter)
        val earnings = doctorEarnings(clinicId, from, toExclusive, patientFilter)
        val visitGross = visits.sumOf { it.grossMinor }
        val visitCollected = visits.sumOf { it.collectedMinor }
        val doctorGross = earnings.sumOf { it.grossMinor }
        val doctorCollected = earnings.sumOf { it.collectedMinor }
        return visitGross == doctorGross && visitCollected == doctorCollected
    }

    data class VisitFinanceTotal(
        val appointmentId: Long,
        val grossMinor: Long,
        val collectedMinor: Long,
        val lines: List<TreatmentPlanLine>,
    )

    data class DoctorEarningAgg(
        val doctorProfileId: Long,
        val visitCount: Int,
        val grossMinor: Long,
        val collectedMinor: Long,
        val outstandingMinor: Long,
    )
}
