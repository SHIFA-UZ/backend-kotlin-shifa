package com.shifa.payments.infrastructure

import com.shifa.payments.domain.PaymentEvent
import com.shifa.payments.domain.PaymentGatewayCode
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentEventRepository : JpaRepository<PaymentEvent, Long> {
    fun existsByGatewayAndEventId(gateway: PaymentGatewayCode, eventId: String): Boolean
    fun findByGatewayAndProcessedFalseOrderByCreatedAtDesc(gateway: PaymentGatewayCode): List<PaymentEvent>
    fun findByGatewayAndFailureReasonIsNotNullOrderByCreatedAtDesc(gateway: PaymentGatewayCode): List<PaymentEvent>
}
