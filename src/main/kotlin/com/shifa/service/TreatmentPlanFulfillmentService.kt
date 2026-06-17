package com.shifa.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.domain.TreatmentPlan
import com.shifa.domain.TreatmentPlanAppointmentLink
import com.shifa.domain.TreatmentPlanLine
import com.shifa.domain.TreatmentPlanLineFulfillment
import com.shifa.repo.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

@Service
class TreatmentPlanFulfillmentService(
    private val plans: TreatmentPlanRepository,
    private val linesRepo: TreatmentPlanLineRepository,
    private val appts: AppointmentRepository,
    private val catalogRepo: TreatmentPlanCatalogItemRepository,
    private val doctors: DoctorProfileRepository,
    private val linkRepo: TreatmentPlanAppointmentLinkRepository,
    private val fulfillmentRepo: TreatmentPlanLineFulfillmentRepository,
    private val financeService: TreatmentPlanFinanceService,
    private val clinicAccess: ClinicAccessService,
    private val users: UserRepository,
    private val objectMapper: ObjectMapper,
) {

    data class FulfillmentCandidateLine(
        val lineId: Long,
        val title: String,
        val fdi: String?,
        val dentition: String?,
        val unitPriceMinor: Long,
        val quantity: Int,
        val discountMinor: Long,
        val currency: String,
        val lineTotalMinor: Long,
        val status: String,
        val toothMatch: Boolean,
    )

    fun isAppointmentLinkedToComprehensivePlan(appointmentId: Long): Boolean {
        if (linesRepo.existsComprehensivePlanLineForAppointment(appointmentId)) return true
        return linkRepo.existsComprehensiveLinkForAppointment(appointmentId)
    }

    fun fulfillmentCandidates(
        principal: Any,
        planId: Long,
        appointmentId: Long?,
    ): List<FulfillmentCandidateLine> {
        loadPlan(principal, planId)
        val appointmentFdiKeys = appointmentId?.let { extractAppointmentFdiKeys(it) } ?: emptySet()
        return linesRepo.findOpenLinesForPlan(planId).map { line ->
            val meta = parseSpecialtyMetadata(line.specialtyMetadata)
            val fdi = meta?.get("fdi")?.toString()
            FulfillmentCandidateLine(
                lineId = line.id,
                title = line.title,
                fdi = fdi,
                dentition = meta?.get("dentition")?.toString(),
                unitPriceMinor = line.unitPriceMinor,
                quantity = line.quantity,
                discountMinor = line.discountMinor,
                currency = line.currency,
                lineTotalMinor = lineTotal(line),
                status = line.status.name,
                toothMatch = fdi != null && appointmentFdiKeys.contains(fdi),
            )
        }
    }

    @Transactional
    fun fulfillLines(
        principal: Any,
        planId: Long,
        appointmentId: Long,
        lineIds: List<Long>,
    ): TreatmentPlan {
        if (lineIds.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one line required")
        }
        val plan = loadPlan(principal, planId)
        if (plan.planKind != TreatmentPlan.PlanKind.COMPREHENSIVE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Only comprehensive plans support fulfillment")
        }
        val appt = appts.findById(appointmentId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found")
        }
        if (appt.patient.id != plan.patient.id) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Appointment patient mismatch")
        }
        clinicAccess.assertCanViewDoctorCalendar(principal, appt.doctor.id!!)
        val uid = clinicAccess.resolveBookingActorUserId(principal)
        val user = users.findById(uid).orElse(null)

        val openById = linesRepo.findOpenLinesForPlan(planId).associateBy { it.id }
        val distinctIds = lineIds.distinct()
        for (lineId in distinctIds) {
            if (lineId !in openById) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Line $lineId is not open for fulfillment")
            }
        }
        val alreadyFulfilled = fulfillmentRepo.findFulfilledLineIdsIn(distinctIds).toSet()
        var primaryLine: TreatmentPlanLine? = null
        val newFulfillments = mutableListOf<TreatmentPlanLineFulfillment>()
        for (lineId in distinctIds) {
            if (lineId in alreadyFulfilled) continue
            val line = openById.getValue(lineId)
            line.status = TreatmentPlanLine.LineStatus.COMPLETED
            line.linkedAppointment = appt
            if (primaryLine == null) primaryLine = line
            newFulfillments.add(
                TreatmentPlanLineFulfillment(
                    line = line,
                    appointment = appt,
                ),
            )
        }
        if (newFulfillments.isNotEmpty()) {
            linesRepo.saveAll(newFulfillments.map { it.line })
            fulfillmentRepo.saveAll(newFulfillments)
            if (appt.linkedTreatmentPlanLine == null && primaryLine != null) {
                appt.linkedTreatmentPlanLine = primaryLine
                appts.save(appt)
            }
        }

        linkRepo.findByPlan_IdAndAppointment_Id(planId, appointmentId)
            ?: linkRepo.save(
                TreatmentPlanAppointmentLink(
                    plan = plan,
                    appointment = appt,
                    billingMode = TreatmentPlanAppointmentLink.BillingMode.FULFILL_PLANNED,
                    createdByUser = user,
                ),
            )

        plan.updatedAt = OffsetDateTime.now()
        plans.save(plan)
        financeService.recalculatePlanTotals(planId)
        return plans.findById(planId).orElseThrow()
    }

    data class AppendLineSpec(
        val catalogItemId: Long?,
        val title: String,
        val quantity: Int,
        val unitPriceMinor: Long,
        val discountMinor: Long,
        val currency: String,
        val specialtyMetadata: String?,
        val assignedDoctorId: Long?,
        val notes: String?,
    )

    @Transactional
    fun appendLines(
        principal: Any,
        planId: Long,
        appointmentId: Long?,
        rows: List<AppendLineSpec>,
    ): TreatmentPlan {
        if (rows.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one line required")
        }
        val plan = loadPlan(principal, planId)
        if (plan.planKind != TreatmentPlan.PlanKind.COMPREHENSIVE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Only comprehensive plans support append")
        }
        val appt = appointmentId?.let { aid ->
            val a = appts.findById(aid).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found")
            }
            if (a.patient.id != plan.patient.id) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Appointment patient mismatch")
            }
            clinicAccess.assertCanViewDoctorCalendar(principal, a.doctor.id!!)
            a
        }
        val uid = clinicAccess.resolveBookingActorUserId(principal)
        val user = users.findById(uid).orElse(null)

        var order = linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(planId).maxOfOrNull { it.sortOrder } ?: -1
        var firstLine: TreatmentPlanLine? = null
        for (row in rows) {
            order += 1
            val catalog = row.catalogItemId?.let { cid ->
                catalogRepo.findById(cid).orElseThrow {
                    ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad catalog item")
                }.also { c ->
                    if (c.clinic.id != plan.clinic.id) {
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Catalog mismatch")
                    }
                }
            }
            val assignedDoctor = row.assignedDoctorId?.let { did ->
                doctors.findById(did).orElseThrow {
                    ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found")
                }
            }
            val line = TreatmentPlanLine(
                plan = plan,
                catalogItem = catalog,
                title = row.title.trim(),
                quantity = row.quantity.coerceAtLeast(1),
                unitPriceMinor = row.unitPriceMinor,
                discountMinor = row.discountMinor,
                currency = row.currency,
                sortOrder = order,
                linkedAppointment = appt,
                status = if (appt != null) {
                    TreatmentPlanLine.LineStatus.COMPLETED
                } else {
                    TreatmentPlanLine.LineStatus.PLANNED
                },
                specialtyMetadata = row.specialtyMetadata,
                notes = row.notes,
            )
            line.assignedDoctor = assignedDoctor
            val saved = linesRepo.save(line)
            if (firstLine == null) firstLine = saved
        }

        if (appt != null) {
            if (appt.linkedTreatmentPlanLine == null && firstLine != null) {
                appt.linkedTreatmentPlanLine = firstLine
                appts.save(appt)
            }
            linkRepo.findByPlan_IdAndAppointment_Id(planId, appt.id)
                ?: linkRepo.save(
                    TreatmentPlanAppointmentLink(
                        plan = plan,
                        appointment = appt,
                        billingMode = TreatmentPlanAppointmentLink.BillingMode.ADD_EXTRA,
                        createdByUser = user,
                    ),
                )
        }

        plan.updatedAt = OffsetDateTime.now()
        plans.save(plan)
        financeService.recalculatePlanTotals(planId)
        return plans.findById(planId).orElseThrow()
    }

    @Transactional
    fun revertFulfillmentsForCancelledAppointment(appointmentId: Long) {
        val fulfillments = fulfillmentRepo.findByAppointmentIdWithLineAndPlan(appointmentId)
        if (fulfillments.isEmpty()) {
            linkRepo.findByAppointment_Id(appointmentId).forEach { linkRepo.delete(it) }
            return
        }
        val planIds = mutableSetOf<Long>()
        val fulfilledLineIds = mutableSetOf<Long>()
        val linesToRevert = mutableListOf<TreatmentPlanLine>()
        for (f in fulfillments) {
            val line = f.line
            planIds.add(line.plan.id)
            fulfilledLineIds.add(line.id)
            fulfillmentRepo.delete(f)
            line.status = TreatmentPlanLine.LineStatus.PLANNED
            if (line.linkedAppointment?.id == appointmentId) {
                line.linkedAppointment = null
            }
            linesToRevert.add(line)
        }
        if (linesToRevert.isNotEmpty()) {
            linesRepo.saveAll(linesToRevert)
        }
        linkRepo.findByAppointment_Id(appointmentId).forEach { linkRepo.delete(it) }
        val appt = appts.findById(appointmentId).orElse(null)
        if (appt != null) {
            val primary = appt.linkedTreatmentPlanLine
            if (primary != null && primary.id in fulfilledLineIds) {
                appt.linkedTreatmentPlanLine = null
            }
            appts.save(appt)
        }
        if (planIds.size == 1) {
            financeService.recalculatePlanTotals(planIds.first())
        } else {
            for (planId in planIds) {
                financeService.recalculatePlanTotals(planId)
            }
        }
    }

    private fun loadPlan(principal: Any, planId: Long): TreatmentPlan {
        val plan = plans.findById(planId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")
        }
        clinicAccess.assertPrincipalMayAccessClinic(principal, plan.clinic.id!!)
        clinicAccess.assertPatientVisible(principal, plan.patient.id!!)
        return plan
    }

    private fun lineTotal(line: TreatmentPlanLine): Long =
        (line.unitPriceMinor * line.quantity - line.discountMinor).coerceAtLeast(0)

    private fun parseSpecialtyMetadata(json: String?): Map<String, Any?>? {
        if (json.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(json, object : TypeReference<Map<String, Any?>>() {})
        } catch (_: Exception) {
            null
        }
    }

    private fun extractAppointmentFdiKeys(appointmentId: Long): Set<String> {
        val appt = appts.findById(appointmentId).orElse(null) ?: return emptySet()
        val raw = appt.dentalDocumentation?.trim().orEmpty()
        if (raw.isEmpty()) return emptySet()
        return try {
            val parsed = objectMapper.readValue(raw, object : TypeReference<Map<String, Any?>>() {})
            val teeth = parsed["teeth"] as? Map<*, *> ?: return emptySet()
            teeth.keys.map { it.toString() }.filter { it != "__general__" }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
