package com.shifa.repo

import com.shifa.domain.TreatmentPlanLine
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TreatmentPlanLineRepository : JpaRepository<TreatmentPlanLine, Long> {
    fun findByPlan_IdOrderBySortOrderAscIdAsc(planId: Long): List<TreatmentPlanLine>

    @Query(
        """
        SELECT COUNT(DISTINCT l.linkedAppointment.id) FROM TreatmentPlanLine l
        WHERE l.plan.id = :planId AND l.linkedAppointment IS NOT NULL
        """,
    )
    fun countDistinctLinkedAppointmentsByPlanId(planId: Long): Long

    /** Batch visit counts for plan list screens — one query instead of N per row. */
    @Query(
        value = """
            SELECT l.plan_id, COUNT(DISTINCT l.linked_appointment_id)
            FROM treatment_plan_lines l
            WHERE l.plan_id IN (:planIds) AND l.linked_appointment_id IS NOT NULL
            GROUP BY l.plan_id
            """,
        nativeQuery = true,
    )
    fun countDistinctLinkedAppointmentsGrouped(planIds: Collection<Long>): List<Array<Any>>

    @Query(
        """
        SELECT l FROM TreatmentPlanLine l
        JOIN FETCH l.linkedAppointment a
        JOIN FETCH a.doctor
        WHERE l.plan.id = :planId AND l.linkedAppointment IS NOT NULL
        ORDER BY a.startAt DESC
        """
    )
    fun findLinkedAppointmentLinesForPlan(planId: Long): List<TreatmentPlanLine>

    fun findByLinkedAppointment_Id(appointmentId: Long): List<TreatmentPlanLine>

    @Query(
        """
        SELECT CASE WHEN COUNT(l) > 0 THEN true ELSE false END
        FROM TreatmentPlanLine l
        JOIN l.plan p
        WHERE l.linkedAppointment.id = :appointmentId
          AND p.planKind = com.shifa.domain.TreatmentPlan.PlanKind.COMPREHENSIVE
        """,
    )
    fun existsComprehensivePlanLineForAppointment(@Param("appointmentId") appointmentId: Long): Boolean

    @Query(
        """
        SELECT l FROM TreatmentPlanLine l
        JOIN FETCH l.plan p
        LEFT JOIN FETCH l.catalogItem
        WHERE p.id = :planId
          AND l.status IN (
            com.shifa.domain.TreatmentPlanLine.LineStatus.PLANNED,
            com.shifa.domain.TreatmentPlanLine.LineStatus.SCHEDULED,
            com.shifa.domain.TreatmentPlanLine.LineStatus.IN_PROGRESS
          )
        ORDER BY l.sortOrder ASC, l.id ASC
        """,
    )
    fun findOpenLinesForPlan(@Param("planId") planId: Long): List<TreatmentPlanLine>

    @Query(
        """
        SELECT l FROM TreatmentPlanLine l
        JOIN FETCH l.plan p
        WHERE p.id = :planId AND l.id IN :lineIds
        """,
    )
    fun findByPlanIdAndIdIn(
        @Param("planId") planId: Long,
        @Param("lineIds") lineIds: Collection<Long>,
    ): List<TreatmentPlanLine>

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
        value = """
            SELECT DISTINCT l.linked_appointment_id FROM treatment_plan_lines l
            INNER JOIN treatment_plans p ON l.plan_id = p.id
            INNER JOIN appointments a ON a.id = l.linked_appointment_id
            WHERE p.clinic_id = :clinicId AND l.linked_appointment_id IS NOT NULL
            AND a.status <> 'CANCELLED'
            AND a.start_at >= :from AND a.start_at < :toExclusive
            ORDER BY a.start_at DESC, l.linked_appointment_id DESC
            """,
        countQuery = """
            SELECT COUNT(DISTINCT l.linked_appointment_id) FROM treatment_plan_lines l
            INNER JOIN treatment_plans p ON l.plan_id = p.id
            INNER JOIN appointments a ON a.id = l.linked_appointment_id
            WHERE p.clinic_id = :clinicId AND l.linked_appointment_id IS NOT NULL
            AND a.status <> 'CANCELLED'
            AND a.start_at >= :from AND a.start_at < :toExclusive
            """,
        nativeQuery = true,
    )
    fun findDistinctLinkedAppointmentIdsForClinicInRange(
        clinicId: Long,
        from: java.time.Instant,
        toExclusive: java.time.Instant,
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
            AND a.start_at >= :from AND a.start_at < :toExclusive
            ORDER BY a.start_at DESC, l.linked_appointment_id DESC
            """,
        countQuery = """
            SELECT COUNT(DISTINCT l.linked_appointment_id) FROM treatment_plan_lines l
            INNER JOIN treatment_plans p ON l.plan_id = p.id
            INNER JOIN appointments a ON a.id = l.linked_appointment_id
            WHERE p.clinic_id = :clinicId AND l.linked_appointment_id IS NOT NULL
            AND a.status <> 'CANCELLED'
            AND a.patient_id IN (:patientIds)
            AND a.start_at >= :from AND a.start_at < :toExclusive
            """,
        nativeQuery = true,
    )
    fun findDistinctLinkedAppointmentIdsForClinicAndPatientsInRange(
        clinicId: Long,
        patientIds: List<Long>,
        from: java.time.Instant,
        toExclusive: java.time.Instant,
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
          AND a.status <> com.shifa.domain.Appointment.Status.CANCELLED
        """
    )
    fun findAllLinesForClinicLedger(clinicId: Long): List<TreatmentPlanLine>

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
          AND a.status <> com.shifa.domain.Appointment.Status.CANCELLED
          AND a.startAt >= :from
          AND a.startAt < :toExclusive
        """
    )
    fun findLinesForClinicLedgerInRange(
        clinicId: Long,
        from: java.time.Instant,
        toExclusive: java.time.Instant,
    ): List<TreatmentPlanLine>
}
