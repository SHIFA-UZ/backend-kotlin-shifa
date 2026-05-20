package com.shifa.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.domain.Appointment
import com.shifa.domain.TreatmentPlan
import com.shifa.domain.TreatmentPlanLine
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ClinicRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.TreatmentPlanCatalogItemRepository
import com.shifa.repo.TreatmentPlanLineRepository
import com.shifa.repo.TreatmentPlanRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class VisitChargeService(
    private val appts: AppointmentRepository,
    private val plans: TreatmentPlanRepository,
    private val linesRepo: TreatmentPlanLineRepository,
    private val catalogRepo: TreatmentPlanCatalogItemRepository,
    private val clinics: ClinicRepository,
    private val doctors: DoctorProfileRepository,
    private val clinicAccess: ClinicAccessService,
    private val financeService: TreatmentPlanFinanceService,
    private val objectMapper: ObjectMapper,
) {

    data class VisitChargeLine(val catalogItemId: Long, val quantity: Int = 1, val unitPriceMinor: Long? = null)

    @Transactional
    fun addVisitCharges(
        principal: Any,
        clinicId: Long,
        appointmentId: Long,
        lines: List<VisitChargeLine>,
    ): TreatmentPlan {
        if (lines.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one service line required")
        }
        val appt = appts.findByIdWithDoctorAndPatient(appointmentId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found")
        }
        clinicAccess.assertCanAccessAppointmentResource(principal, appt.doctor.id!!)
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)

        val clinicDoctorIds = doctors.findAllByPracticeClinic_Id(clinicId).mapNotNull { it.id }.toSet()
        if (appt.doctor.id !in clinicDoctorIds) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Appointment doctor is not part of this clinic")
        }

        val patientId = appt.patient.id
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        clinicAccess.assertPatientVisible(principal, patientId)

        val clinic = clinics.findById(clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        }

        val visitPlan = plans.findActiveVisitPlansForPatient(
            clinicId,
            patientId,
            TreatmentPlan.PlanKind.VISIT,
        ).firstOrNull()
            ?: plans.save(
                TreatmentPlan(
                    clinic = clinic,
                    patient = appt.patient,
                    attendingDoctor = appt.doctor,
                    status = TreatmentPlan.Status.ACTIVE,
                    title = "Visit charges",
                    planKind = TreatmentPlan.PlanKind.VISIT,
                ),
            )

        var firstLine: TreatmentPlanLine? = null
        var order = linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(visitPlan.id).maxOfOrNull { it.sortOrder } ?: -1

        for (spec in lines) {
            order += 1
            val catalog = catalogRepo.findById(spec.catalogItemId).orElseThrow {
                ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown catalog item: ${spec.catalogItemId}")
            }
            if (catalog.clinic.id != clinicId) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Catalog item does not belong to this clinic")
            }
            val qty = spec.quantity.coerceAtLeast(1)
            val price = spec.unitPriceMinor ?: catalog.defaultPriceMinor
            val line = TreatmentPlanLine(
                plan = visitPlan,
                catalogItem = catalog,
                title = catalog.title,
                quantity = qty,
                unitPriceMinor = price,
                discountMinor = 0,
                currency = catalog.currency,
                sortOrder = order,
                linkedAppointment = appt,
                status = TreatmentPlanLine.LineStatus.COMPLETED,
            )
            val saved = linesRepo.save(line)
            if (firstLine == null) firstLine = saved
            if (appt.linkedTreatmentPlanLine == null) {
                appt.linkedTreatmentPlanLine = saved
                appts.save(appt)
            }
        }

        visitPlan.updatedAt = java.time.OffsetDateTime.now()
        plans.save(visitPlan)
        financeService.recalculatePlanTotals(visitPlan.id)
        return plans.findById(visitPlan.id).orElseThrow()
    }

    /**
     * Auto-convert priced lines saved inside [Appointment.dentalDocumentation] into
     * a VISIT treatment plan (unpaid by default). Designed to be called from the
     * appointment-complete flow so the doctor doesn't have to re-pick services after
     * already entering them in the per-teeth diagram.
     *
     * - Idempotent: if any line in the VISIT plan is already linked to this
     *   appointment, the method skips (no duplicates on re-completion / retries).
     * - Lines inherit price / currency / title from the stored teeth payload.
     * - Catalog item linkage is preserved when the saved entry exposes it under
     *   `catalogItemId`; otherwise the line is recorded as a free-form charge.
     * - Discount percent (plan-wide in the dental panel) is distributed
     *   proportionally onto each line's `discountMinor` so the finance subtotal
     *   matches the doctor's on-screen total.
     */
    @Transactional
    fun addDentalChargesFromDocumentation(
        principal: Any,
        clinicId: Long,
        appointmentId: Long,
    ): TreatmentPlan? {
        val raw = run {
            val a = appts.findByIdWithDoctorAndPatient(appointmentId).orElse(null) ?: return null
            a.dentalDocumentation?.trim().orEmpty()
        }
        if (raw.isEmpty()) return null

        val parsed: Map<String, Any?> = try {
            objectMapper.readValue(raw, object : TypeReference<Map<String, Any?>>() {})
        } catch (_: Exception) {
            return null
        }
        val teeth = parsed["teeth"] as? Map<*, *> ?: return null

        // Flatten every priced row across all teeth into a list of charges,
        // preserving insertion order for stable sortOrder.
        val charges = mutableListOf<DentalChargeSpec>()
        for ((_, value) in teeth) {
            val list = value as? List<*> ?: continue
            for (row in list) {
                val m = row as? Map<*, *> ?: continue
                val amount = (m["amountMinor"] as? Number)?.toLong() ?: 0L
                if (amount <= 0L) continue
                val title = m["title"]?.toString()?.trim().orEmpty().ifEmpty { "Service" }
                val currency = m["currency"]?.toString()?.trim().orEmpty().ifEmpty { "UZS" }
                val catalogItemId = (m["catalogItemId"] as? Number)?.toLong()
                charges += DentalChargeSpec(
                    title = title,
                    amountMinor = amount,
                    currency = currency,
                    catalogItemId = catalogItemId,
                )
            }
        }
        if (charges.isEmpty()) return null

        val discountPct = (parsed["discountPercent"] as? Number)?.toDouble()?.coerceIn(0.0, 100.0) ?: 0.0

        val appt = appts.findByIdWithDoctorAndPatient(appointmentId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found")
        }
        clinicAccess.assertCanAccessAppointmentResource(principal, appt.doctor.id!!)
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)

        val clinicDoctorIds = doctors.findAllByPracticeClinic_Id(clinicId).mapNotNull { it.id }.toSet()
        if (appt.doctor.id !in clinicDoctorIds) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Appointment doctor is not part of this clinic")
        }
        val patientId = appt.patient.id
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        clinicAccess.assertPatientVisible(principal, patientId)

        val clinic = clinics.findById(clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        }

        val visitPlan = plans.findActiveVisitPlansForPatient(
            clinicId,
            patientId,
            TreatmentPlan.PlanKind.VISIT,
        ).firstOrNull()
            ?: plans.save(
                TreatmentPlan(
                    clinic = clinic,
                    patient = appt.patient,
                    attendingDoctor = appt.doctor,
                    status = TreatmentPlan.Status.ACTIVE,
                    title = "Visit charges",
                    planKind = TreatmentPlan.PlanKind.VISIT,
                ),
            )

        // Idempotency: if any existing line on this VISIT plan is already linked
        // to this appointment, assume we've already converted the dental data
        // (possibly by an earlier completion retry) and skip.
        val existing = linesRepo.findByPlan_IdOrderBySortOrderAscIdAsc(visitPlan.id)
        if (existing.any { it.linkedAppointment?.id == appointmentId }) {
            return visitPlan
        }

        var order = existing.maxOfOrNull { it.sortOrder } ?: -1
        for (spec in charges) {
            order += 1
            val catalog = spec.catalogItemId?.let { id ->
                catalogRepo.findById(id).orElse(null)?.takeIf { it.clinic.id == clinicId }
            }
            val discountMinor = if (discountPct > 0) {
                ((spec.amountMinor.toDouble() * discountPct) / 100.0).toLong()
            } else 0L
            val line = TreatmentPlanLine(
                plan = visitPlan,
                catalogItem = catalog,
                title = spec.title,
                quantity = 1,
                unitPriceMinor = spec.amountMinor,
                discountMinor = discountMinor.coerceAtMost(spec.amountMinor),
                currency = spec.currency,
                sortOrder = order,
                linkedAppointment = appt,
                status = TreatmentPlanLine.LineStatus.COMPLETED,
            )
            val saved = linesRepo.save(line)
            if (appt.linkedTreatmentPlanLine == null) {
                appt.linkedTreatmentPlanLine = saved
                appts.save(appt)
            }
        }

        visitPlan.updatedAt = java.time.OffsetDateTime.now()
        plans.save(visitPlan)
        financeService.recalculatePlanTotals(visitPlan.id)
        return plans.findById(visitPlan.id).orElseThrow()
    }

    private data class DentalChargeSpec(
        val title: String,
        val amountMinor: Long,
        val currency: String,
        val catalogItemId: Long?,
    )
}
