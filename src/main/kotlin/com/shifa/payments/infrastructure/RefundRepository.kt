package com.shifa.payments.infrastructure

import com.shifa.payments.domain.Refund
import org.springframework.data.jpa.repository.JpaRepository

interface RefundRepository : JpaRepository<Refund, Long> {
    fun findByPayment_Id(paymentId: Long): List<Refund>
}
