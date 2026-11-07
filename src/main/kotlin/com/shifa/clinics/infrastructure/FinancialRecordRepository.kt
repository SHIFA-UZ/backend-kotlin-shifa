package com.shifa.clinics.infrastructure

import com.shifa.clinics.domain.FinancialRecord
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

interface FinancialRecordRepository : JpaRepository<FinancialRecord, Long> {

    fun findByClinic_IdOrderByCreatedAtDesc(clinicId: Long, pageable: Pageable): Page<FinancialRecord>

    fun findByClinic_IdAndStatus(clinicId: Long, status: FinancialRecord.Status, pageable: Pageable): Page<FinancialRecord>

    fun findByTreatmentPlan_IdOrderByCreatedAtDesc(treatmentPlanId: Long): List<FinancialRecord>

    fun findByPatient_IdOrderByCreatedAtDesc(patientId: Long): List<FinancialRecord>

    fun findByClinic_IdAndPatient_IdInOrderByCreatedAtDesc(
        clinicId: Long,
        patientIds: Collection<Long>,
        pageable: Pageable,
    ): Page<FinancialRecord>

    fun findByClinic_IdAndStatusAndPatient_IdInOrderByCreatedAtDesc(
        clinicId: Long,
        status: FinancialRecord.Status,
        patientIds: Collection<Long>,
        pageable: Pageable,
    ): Page<FinancialRecord>

    @Query(
        """
        SELECT fr FROM FinancialRecord fr
        WHERE fr.clinic.id = :clinicId
          AND fr.status IN :statuses
          AND fr.remainingMinor > 0
        ORDER BY fr.dueDate ASC NULLS LAST
        """
    )
    fun findByClinicIdAndStatusInAndRemainingMinorGreaterThanZero(
        clinicId: Long,
        statuses: List<FinancialRecord.Status>,
    ): List<FinancialRecord>

    /** Records created in [from, to) (BE-11); [statuses] is every status when the list is not filtered. */
    @Query(
        """
        SELECT fr FROM FinancialRecord fr
        WHERE fr.clinic.id = :clinicId
          AND fr.status IN :statuses
          AND fr.createdAt >= :from AND fr.createdAt < :to
        ORDER BY fr.createdAt DESC
        """
    )
    fun findByClinicInRange(
        clinicId: Long,
        statuses: Collection<FinancialRecord.Status>,
        from: OffsetDateTime,
        to: OffsetDateTime,
        pageable: Pageable,
    ): Page<FinancialRecord>

    @Query(
        """
        SELECT fr FROM FinancialRecord fr
        WHERE fr.clinic.id = :clinicId
          AND fr.status IN :statuses
          AND fr.patient.id IN :patientIds
          AND fr.createdAt >= :from AND fr.createdAt < :to
        ORDER BY fr.createdAt DESC
        """
    )
    fun findByClinicAndPatientsInRange(
        clinicId: Long,
        statuses: Collection<FinancialRecord.Status>,
        patientIds: Collection<Long>,
        from: OffsetDateTime,
        to: OffsetDateTime,
        pageable: Pageable,
    ): Page<FinancialRecord>
}
