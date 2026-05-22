package com.shifa.repo

import com.shifa.domain.TreatmentPlan
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

interface TreatmentPlanRepository : JpaRepository<TreatmentPlan, Long> {
    fun findByClinic_IdAndPatient_IdOrderByCreatedAtDesc(clinicId: Long, patientId: Long): List<TreatmentPlan>

    fun findByClinic_IdOrderByUpdatedAtDescIdDesc(clinicId: Long): List<TreatmentPlan>

    fun findByClinic_IdAndStatus(clinicId: Long, status: TreatmentPlan.Status): List<TreatmentPlan>

    fun findByStatusIn(statuses: List<TreatmentPlan.Status>): List<TreatmentPlan>

    fun findByClinic_IdAndRemainingAmountMinorGreaterThan(clinicId: Long, amount: Long): List<TreatmentPlan>

    @org.springframework.data.jpa.repository.Query(
        """
        SELECT p FROM TreatmentPlan p
        WHERE p.clinic.id = :clinicId AND p.patient.id = :patientId
          AND p.planKind = :planKind
          AND p.status <> 'CANCELLED'
        ORDER BY p.createdAt DESC
        """
    )
    fun findActiveVisitPlansForPatient(
        clinicId: Long,
        patientId: Long,
        planKind: TreatmentPlan.PlanKind,
    ): List<TreatmentPlan>

    @Query(
        """SELECT COUNT(tp) FROM TreatmentPlan tp WHERE tp.attendingDoctor IS NOT NULL
           AND tp.attendingDoctor.id = :doctorId AND tp.createdAt >= :start AND tp.createdAt < :endExclusive"""
    )
    fun countByAttendingDoctorInDateRange(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: OffsetDateTime,
        @Param("endExclusive") endExclusive: OffsetDateTime,
    ): Long

    @Query("""
        SELECT COUNT(tp) FROM TreatmentPlan tp WHERE tp.attendingDoctor IS NOT NULL AND tp.attendingDoctor.id = :doctorId
    """)
    fun countByAttendingDoctorAllTime(@Param("doctorId") doctorId: Long): Long

    @Query("SELECT MAX(p.updatedAt) FROM TreatmentPlan p WHERE p.attendingDoctor IS NOT NULL AND p.attendingDoctor.id = :doctorId")
    fun findMaxUpdatedAtByAttendingDoctorId(@Param("doctorId") doctorId: Long): OffsetDateTime?

    @Query("""
        SELECT tp FROM TreatmentPlan tp WHERE tp.attendingDoctor IS NOT NULL AND tp.attendingDoctor.id = :doctorId
        AND tp.createdAt >= :start AND tp.createdAt < :endExclusive""")
    fun listByAttendingDoctorCreatedBetween(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: OffsetDateTime,
        @Param("endExclusive") endExclusive: OffsetDateTime,
    ): List<TreatmentPlan>
}
