package com.shifa.service

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
}
