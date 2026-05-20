package com.shifa.service

import com.shifa.domain.TreatmentPlanLine
import com.shifa.repo.TreatmentPlanLineRepository
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ClinicFinanceLedgerService(
    private val linesRepo: TreatmentPlanLineRepository,
) {

    fun lineTotal(line: TreatmentPlanLine): Long =
        (line.unitPriceMinor * line.quantity - line.discountMinor).coerceAtLeast(0)

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

    fun doctorEarnings(
        clinicId: Long,
        from: Instant,
        toExclusive: Instant,
        patientFilter: Set<Long>?,
    ): List<DoctorEarningAgg> {
        val lines = linesRepo.findLinesForClinicLedgerInRange(clinicId, from, toExclusive)
        val filtered =
            if (patientFilter == null) lines
            else lines.filter { line ->
                val pid = line.linkedAppointment?.patient?.id ?: return@filter false
                pid in patientFilter
            }
        if (filtered.isEmpty()) return emptyList()

        data class MutableAgg(var grossMinor: Long, var collectedMinor: Long, var visits: MutableSet<Long>)

        val byDoctor = mutableMapOf<Long, MutableAgg>()
        val byAppt = filtered.groupBy { it.linkedAppointment!!.id }
        for ((_, apptLines) in byAppt) {
            val appt = apptLines.first().linkedAppointment!!
            val doctorId = appt.doctor.id ?: continue
            val patientId = appt.patient.id
            if (patientFilter != null && patientId != null && patientId !in patientFilter) continue
            val visitTotal = apptLines.sumOf { lineTotal(it) }
            val plan = apptLines.first().plan
            val planTotal = plan.estimatedTotalMinor.coerceAtLeast(1L)
            val allocated = (plan.paidAmountMinor * visitTotal) / planTotal
            val agg = byDoctor.getOrPut(doctorId) { MutableAgg(0L, 0L, mutableSetOf()) }
            agg.grossMinor += visitTotal
            agg.collectedMinor += allocated
            agg.visits.add(appt.id)
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

    data class DoctorEarningAgg(
        val doctorProfileId: Long,
        val visitCount: Int,
        val grossMinor: Long,
        val collectedMinor: Long,
        val outstandingMinor: Long,
    )
}
