package com.shifa.payment.gateway

import com.shifa.payment.domain.PaymentGatewayCode
import org.springframework.stereotype.Component

@Component
class PaymentGatewayRouter(
    gateways: List<PaymentGateway>
) {
    private val byCode: Map<PaymentGatewayCode, PaymentGateway> = gateways.associateBy { it.code }

    fun getOrDefault(code: PaymentGatewayCode?): PaymentGateway {
        return byCode[code] ?: byCode[PaymentGatewayCode.STRIPE] ?: byCode[PaymentGatewayCode.MANUAL]
        ?: error("No payment gateway implementation registered")
    }
}
