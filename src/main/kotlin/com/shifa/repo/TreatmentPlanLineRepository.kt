package com.shifa.repo

import com.shifa.domain.TreatmentPlanLine
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TreatmentPlanLineRepository : JpaRepository<TreatmentPlanLine, Long> {
    fun findByPlan_IdOrderBySortOrderAscIdAsc(planId: Long): List<TreatmentPlanLine>

    fun findByLinkedAppointment_Id(appointmentId: Long): List<TreatmentPlanLine>

    @Query(
        value = """
            SELECT DISTINCT l.linked_appointment_id FROM treatment_plan_lines l
            INNER JOIN treatment_plans p ON l.plan_id = p.id
            INNER JOIN appointments a ON a.id = l.linked_appointment_id
            WHERE p.clinic_id = :clinicId AND l.linked_appointment_id IS NOT NULL
            AND a.status <> 'CANCELLED'
            ORDER BY l.linked_appointment_id DESC
            """,
        countQuery = """
            SELECT COUNT(DISTINCT l.linked_appointment_id) FROM treatment_plan_lines l
            INNER JOIN treatment_plans p ON l.plan_id = p.id
            INNER JOIN appointments a ON a.id = l.linked_appointment_id
            WHERE p.clinic_id = :clinicId AND l.linked_appointment_id IS NOT NULL
            AND a.status <> 'CANCELLED'
            """,
        nativeQuery = true,
    )
    fun findDistinctLinkedAppointmentIdsForClinic(
        clinicId: Long,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<Long>

    @Query(
        value = """
            SELECT DISTINCT l.linked_appointment_id FROM treatment_plan_lines l
            INNER JOIN treatment_plans p ON l.plan_id = p.id
            INNER JOIN appointments a ON a.id = l.linked_appointment_id
            WHERE p.clinic_id = :clinicId AND l.linked_appointment_id IS NOT NULL
            AND a.status <> 'CANCELLED'
            AND a.patient_id IN (:patientIds)
            ORDER BY l.linked_appointment_id DESC
            """,
        countQuery = """
            SELECT COUNT(DISTINCT l.linked_appointment_id) FROM treatment_plan_lines l
            INNER JOIN treatment_plans p ON l.plan_id = p.id
            INNER JOIN appointments a ON a.id = l.linked_appointment_id
            WHERE p.clinic_id = :clinicId AND l.linked_appointment_id IS NOT NULL
            AND a.status <> 'CANCELLED'
            AND a.patient_id IN (:patientIds)
            """,
        nativeQuery = true,
    )
    fun findDistinctLinkedAppointmentIdsForClinicAndPatients(
        clinicId: Long,
        patientIds: List<Long>,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<Long>

    @Query(
        """
        SELECT l FROM TreatmentPlanLine l
        JOIN FETCH l.linkedAppointment a
        JOIN FETCH a.doctor
        JOIN FETCH a.patient
        JOIN FETCH l.plan p
        LEFT JOIN FETCH l.assignedDoctor
        WHERE p.clinic.id = :clinicId
          AND l.linkedAppointment IS NOT NULL
          AND a.status <> 'CANCELLED'
          AND (:from IS NULL OR a.startAt >= :from)
          AND (:toExclusive IS NULL OR a.startAt < :toExclusive)
        """
    )
    fun findLinesForClinicLedger(
        clinicId: Long,
        from: java.time.Instant?,
        toExclusive: java.time.Instant?,
    ): List<TreatmentPlanLine>
}
