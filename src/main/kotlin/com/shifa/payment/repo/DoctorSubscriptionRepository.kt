package com.shifa.payment.repo

import com.shifa.payment.domain.DoctorSubscription
import org.springframework.data.jpa.repository.JpaRepository

interface DoctorSubscriptionRepository : JpaRepository<DoctorSubscription, Long> {
    fun findTopByDoctor_IdOrderByCreatedAtDesc(doctorId: Long): DoctorSubscription?
    fun findTopByExternalSubscriptionIdOrderByCreatedAtDesc(externalSubscriptionId: String): DoctorSubscription?
}
