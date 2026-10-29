package com.shifa.clinics.application

import com.shifa.clinics.domain.FinancialRecord
import com.shifa.clinics.infrastructure.FinancialRecordRepository
import java.util.Optional
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

/**
 * The feature's door to FinancialRecord records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [FinancialRecordRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class FinancialRecords(private val repo: FinancialRecordRepository) {
    fun findByClinic_IdOrderByCreatedAtDesc(clinicId: Long, pageable: Pageable): Page<FinancialRecord> = repo.findByClinic_IdOrderByCreatedAtDesc(clinicId, pageable)
    fun findByClinic_IdAndStatus(clinicId: Long, status: FinancialRecord.Status, pageable: Pageable): Page<FinancialRecord> = repo.findByClinic_IdAndStatus(clinicId, status, pageable)
    fun findByClinic_IdAndPatient_IdInOrderByCreatedAtDesc(clinicId: Long, patientIds: Collection<Long>, pageable: Pageable): Page<FinancialRecord> = repo.findByClinic_IdAndPatient_IdInOrderByCreatedAtDesc(clinicId, patientIds, pageable)
    fun findByClinic_IdAndStatusAndPatient_IdInOrderByCreatedAtDesc(clinicId: Long, status: FinancialRecord.Status, patientIds: Collection<Long>, pageable: Pageable): Page<FinancialRecord> = repo.findByClinic_IdAndStatusAndPatient_IdInOrderByCreatedAtDesc(clinicId, status, patientIds, pageable)
    fun findByClinicIdAndStatusInAndRemainingMinorGreaterThanZero(clinicId: Long, statuses: List<FinancialRecord.Status>): List<FinancialRecord> = repo.findByClinicIdAndStatusInAndRemainingMinorGreaterThanZero(clinicId, statuses)
    fun findById(id: Long): Optional<FinancialRecord> = repo.findById(id)
    fun save(entity: FinancialRecord): FinancialRecord = repo.save(entity)
}
