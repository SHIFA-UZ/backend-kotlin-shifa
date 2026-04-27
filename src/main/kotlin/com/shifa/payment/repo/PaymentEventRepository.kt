package com.shifa.payment.repo

import com.shifa.payment.domain.PaymentEvent
import com.shifa.payment.domain.PaymentGatewayCode
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentEventRepository : JpaRepository<PaymentEvent, Long> {
    fun existsByGatewayAndEventId(gateway: PaymentGatewayCode, eventId: String): Boolean
}
