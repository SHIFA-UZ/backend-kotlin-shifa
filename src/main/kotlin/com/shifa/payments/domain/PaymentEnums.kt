package com.shifa.payments.domain

enum class PaymentGatewayCode {
    STRIPE,
    CLICK,
    PAYME,
    MANUAL
}

enum class PaymentKind {
    CONSULTATION,
    SUBSCRIPTION,
    /** Patient online payment against a clinic treatment-plan balance (Click). */
    TREATMENT_PLAN
}

enum class PaymentStatus {
    PENDING,
    AUTHORIZED,
    PAID,
    FAILED,
    CANCELLED,
    REFUNDED
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
