package com.shifa.payments.infrastructure

import com.shifa.payments.domain.DoctorSubscription
import org.springframework.data.jpa.repository.JpaRepository

interface DoctorSubscriptionRepository : JpaRepository<DoctorSubscription, Long> {
    fun findTopByDoctor_IdOrderByCreatedAtDesc(doctorId: Long): DoctorSubscription?
    fun findTopByExternalSubscriptionIdOrderByCreatedAtDesc(externalSubscriptionId: String): DoctorSubscription?
}
