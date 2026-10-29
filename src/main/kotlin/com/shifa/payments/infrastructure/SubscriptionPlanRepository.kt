package com.shifa.payments.infrastructure

import com.shifa.payments.domain.SubscriptionPlan
import org.springframework.data.jpa.repository.JpaRepository

interface SubscriptionPlanRepository : JpaRepository<SubscriptionPlan, Long> {
    fun findByCode(code: String): SubscriptionPlan?
    fun findByEnabledTrueOrderByPriceMinorAsc(): List<SubscriptionPlan>
}
