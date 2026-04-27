package com.shifa.payment.gateway

import com.shifa.payment.domain.PaymentGatewayCode
import org.springframework.stereotype.Component

/**
 * Placeholder gateway used until Stripe/Click/Payme are wired.
 * It creates a deterministic checkout URL that frontend can display in non-prod.
 */
@Component
class ManualPaymentGateway : PaymentGateway {
    override val code: PaymentGatewayCode = PaymentGatewayCode.MANUAL

    override fun createCheckout(request: GatewayCheckoutRequest): GatewayCheckoutResult {
        val fakeId = "manual_${request.externalRef}"
        val fakeUrl = request.successUrl ?: "https://example.com/payments/$fakeId"
        return GatewayCheckoutResult(
            gatewayPaymentId = fakeId,
            checkoutUrl = fakeUrl,
            status = GatewayCheckoutStatus.PENDING
        )
    }
}
