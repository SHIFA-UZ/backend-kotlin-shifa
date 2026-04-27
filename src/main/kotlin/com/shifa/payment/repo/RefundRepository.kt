package com.shifa.payment.repo

import com.shifa.payment.domain.Refund
import org.springframework.data.jpa.repository.JpaRepository

interface RefundRepository : JpaRepository<Refund, Long> {
    fun findByPayment_Id(paymentId: Long): List<Refund>
}
