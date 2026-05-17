package com.shifa.repo

import com.shifa.domain.TreatmentPlanLine
import org.springframework.data.jpa.repository.JpaRepository

interface TreatmentPlanLineRepository : JpaRepository<TreatmentPlanLine, Long> {
    fun findByPlan_IdOrderBySortOrderAscIdAsc(planId: Long): List<TreatmentPlanLine>
}
