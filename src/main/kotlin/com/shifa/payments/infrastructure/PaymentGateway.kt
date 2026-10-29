package com.shifa.payments.infrastructure

import com.shifa.payments.domain.PaymentGatewayCode
import com.shifa.payments.domain.PaymentKind

interface PaymentGateway {
    val code: PaymentGatewayCode

    fun createCheckout(request: GatewayCheckoutRequest): GatewayCheckoutResult
}

data class GatewayCheckoutRequest(
    val externalRef: String,
    val amountMinor: Long,
    val currency: String,
    val kind: PaymentKind,
    val description: String,
    val destinationAccountId: String? = null,
    val successUrl: String?,
    val cancelUrl: String?
)

data class GatewayCheckoutResult(
    val gatewayPaymentId: String,
    val checkoutUrl: String,
    val status: GatewayCheckoutStatus
)

enum class GatewayCheckoutStatus {
    PENDING,
    PAID
}
