package com.shifa.repo

import com.shifa.domain.InstallmentItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface InstallmentItemRepository : JpaRepository<InstallmentItem, Long> {

    fun findByInstallmentPlan_IdOrderBySequenceNumberAsc(installmentPlanId: Long): List<InstallmentItem>

    @Query("""
        SELECT ii FROM InstallmentItem ii
        JOIN ii.installmentPlan ip
        JOIN ip.treatmentPlan tp
        WHERE tp.clinic.id = :clinicId
          AND ii.status = :status
          AND ii.dueDate < :today
        ORDER BY ii.dueDate ASC
    """)
    fun findOverdueByClinic(clinicId: Long, status: InstallmentItem.Status, today: LocalDate): List<InstallmentItem>

    @Query("""
        SELECT ii FROM InstallmentItem ii
        JOIN ii.installmentPlan ip
        WHERE ip.id = :installmentPlanId
          AND ii.status IN :statuses
        ORDER BY ii.sequenceNumber ASC
    """)
    fun findUnpaidByPlan(installmentPlanId: Long, statuses: List<InstallmentItem.Status>): List<InstallmentItem>
}
