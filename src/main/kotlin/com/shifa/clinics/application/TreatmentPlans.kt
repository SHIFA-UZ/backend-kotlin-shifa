package com.shifa.clinics.application

import com.shifa.clinics.domain.TreatmentPlan
import com.shifa.clinics.infrastructure.TreatmentPlanRepository
import java.time.OffsetDateTime
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to TreatmentPlan records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [TreatmentPlanRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class TreatmentPlans(private val repo: TreatmentPlanRepository) {
    fun findByClinic_IdAndPatient_IdOrderByCreatedAtDesc(clinicId: Long, patientId: Long): List<TreatmentPlan> = repo.findByClinic_IdAndPatient_IdOrderByCreatedAtDesc(clinicId, patientId)
    fun findByClinic_IdOrderByUpdatedAtDescIdDesc(clinicId: Long): List<TreatmentPlan> = repo.findByClinic_IdOrderByUpdatedAtDescIdDesc(clinicId)
    fun findActivePlansEligibleForPaymentReminder(statuses: List<TreatmentPlan.Status>, earliestEligible: OffsetDateTime): List<TreatmentPlan> = repo.findActivePlansEligibleForPaymentReminder(statuses, earliestEligible)
    fun countByAttendingDoctorInDateRange(doctorId: Long, start: OffsetDateTime, endExclusive: OffsetDateTime): Long = repo.countByAttendingDoctorInDateRange(doctorId, start, endExclusive)
    fun countByAttendingDoctorAllTime(doctorId: Long): Long = repo.countByAttendingDoctorAllTime(doctorId)
    fun findMaxUpdatedAtByAttendingDoctorId(doctorId: Long): OffsetDateTime? = repo.findMaxUpdatedAtByAttendingDoctorId(doctorId)
    fun listByAttendingDoctorCreatedBetween(doctorId: Long, start: OffsetDateTime, endExclusive: OffsetDateTime): List<TreatmentPlan> = repo.listByAttendingDoctorCreatedBetween(doctorId, start, endExclusive)
    fun findById(id: Long): Optional<TreatmentPlan> = repo.findById(id)
    fun save(entity: TreatmentPlan): TreatmentPlan = repo.save(entity)
}
