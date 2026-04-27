package com.shifa.payment.domain

enum class PaymentGatewayCode {
    STRIPE,
    CLICK,
    PAYME,
    MANUAL
}

enum class PaymentKind {
    CONSULTATION,
    SUBSCRIPTION
}

enum class PaymentStatus {
    PENDING,
    AUTHORIZED,
    PAID,
    FAILED,
    CANCELLED,
    REFUNDED
}

enum class RefundStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}

enum class SubscriptionInterval {
    MONTH,
    YEAR
}

enum class DoctorSubscriptionStatus {
    TRIALING,
    ACTIVE,
    PAST_DUE,
    CANCELLED
}
