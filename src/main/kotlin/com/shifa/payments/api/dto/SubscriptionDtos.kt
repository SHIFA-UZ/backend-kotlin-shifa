package com.shifa.payments.api.dto

import com.shifa.payments.domain.PaymentGatewayCode

data class StartSubscriptionRequest(
    val planCode: String,
    val gateway: PaymentGatewayCode = PaymentGatewayCode.STRIPE
)

data class SubscriptionDto(
    val id: Long,
    val planCode: String,
    val status: String,
    val gateway: String,
    val currentPeriodEnd: String?,
    val gracePeriodEndsAt: String?,
    val suspendedAt: String?
)
