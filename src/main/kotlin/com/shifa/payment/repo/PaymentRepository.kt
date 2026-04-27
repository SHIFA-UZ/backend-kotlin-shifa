package com.shifa.payment.repo

import com.shifa.payment.domain.Payment
import com.shifa.payment.domain.PaymentKind
import com.shifa.payment.domain.PaymentStatus
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentRepository : JpaRepository<Payment, Long> {
    fun findByExternalRef(externalRef: String): Payment?
    fun findByAppointment_Id(appointmentId: Long): Payment?
    fun findByGatewayPaymentId(gatewayPaymentId: String): Payment?
    fun findByKindAndStatus(kind: PaymentKind, status: PaymentStatus): List<Payment>
}
