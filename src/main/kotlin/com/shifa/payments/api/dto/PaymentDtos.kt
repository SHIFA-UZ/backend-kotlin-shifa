package com.shifa.payments.api.dto

import com.shifa.payments.domain.PaymentGatewayCode

data class ConsultationCheckoutRequest(
    val appointmentId: Long,
    val gateway: PaymentGatewayCode? = null,
    /** "app": the gateway return pages redirect back into the patient app (ask 12). */
    val platform: String? = null,
)

data class TreatmentPlanCheckoutRequest(
    val treatmentPlanId: Long,
    val installmentItemId: Long? = null,
    val gateway: PaymentGatewayCode? = null,
    /** "app": the gateway return pages redirect back into the patient app (ask 12). */
    val platform: String? = null,
)

data class PaymentStatusDto(
    val id: Long,
    val status: String,
    val gateway: String,
    val amountMinor: Long,
    val currency: String,
    val checkoutUrl: String?,
    /** Why the payment failed, was cancelled or expired; null while open or paid (patient-app ask 12). */
    val failureReason: String? = null,
)
