package com.shifa.repo

import com.shifa.domain.InstallmentItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.time.OffsetDateTime

interface InstallmentItemRepository : JpaRepository<InstallmentItem, Long> {

    fun findByInstallmentPlan_IdOrderBySequenceNumberAsc(installmentPlanId: Long): List<InstallmentItem>

    @Query("""
        SELECT ii FROM InstallmentItem ii
        JOIN ii.installmentPlan ip
        JOIN ip.treatmentPlan tp
        WHERE tp.clinic.id = :clinicId
          AND ((ii.status = 'OVERDUE') OR (ii.status = 'PENDING' AND ii.dueDate < :today))
        ORDER BY ii.dueDate ASC
    """)
    fun findOverdueByClinic(clinicId: Long, today: LocalDate): List<InstallmentItem>

    @Query("""
        SELECT ii FROM InstallmentItem ii
        JOIN FETCH ii.installmentPlan ip
        JOIN FETCH ip.treatmentPlan tp
        JOIN FETCH tp.patient
        WHERE tp.clinic.id = :clinicId
          AND ii.status IN ('PENDING', 'OVERDUE', 'PAID')
        ORDER BY ii.dueDate ASC, ii.sequenceNumber ASC
    """)
    fun findActiveByClinic(@Param("clinicId") clinicId: Long): List<InstallmentItem>

    @Query("""
        SELECT ii FROM InstallmentItem ii
        JOIN ii.installmentPlan ip
        WHERE ip.id = :installmentPlanId
          AND ii.status IN :statuses
        ORDER BY ii.sequenceNumber ASC
    """)
    fun findUnpaidByPlan(installmentPlanId: Long, statuses: List<InstallmentItem.Status>): List<InstallmentItem>

    @Query(
        """
        SELECT ii FROM InstallmentItem ii
        JOIN FETCH ii.installmentPlan ip
        JOIN FETCH ip.treatmentPlan tp
        JOIN FETCH tp.clinic
        WHERE ii.status = 'PENDING' AND ii.dueDate < :today
        """
    )
    fun findAllPendingPastDue(@Param("today") today: LocalDate): List<InstallmentItem>

    @Query(
        """
        SELECT ii FROM InstallmentItem ii
        JOIN FETCH ii.installmentPlan ip
        JOIN FETCH ip.treatmentPlan tp
        JOIN FETCH tp.patient
        WHERE ii.status = 'PENDING' AND ii.dueDate = :dueDate
        """
    )
    fun findAllPendingDueOn(@Param("dueDate") dueDate: LocalDate): List<InstallmentItem>

    @Query(
        """
        SELECT ii FROM InstallmentItem ii
        JOIN FETCH ii.installmentPlan ip
        JOIN FETCH ip.treatmentPlan tp
        JOIN FETCH tp.patient
        WHERE ii.status = 'OVERDUE'
          AND (ii.lastReminderSentAt IS NULL OR ii.lastReminderSentAt < :before)
        """
    )
    fun findOverdueNeedingRemind(@Param("before") before: OffsetDateTime): List<InstallmentItem>
}
