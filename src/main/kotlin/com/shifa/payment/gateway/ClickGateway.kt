package com.shifa.payment.gateway

import com.shifa.config.ClickProperties
import com.shifa.payment.domain.PaymentGatewayCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.util.UriComponentsBuilder

@Component
class ClickGateway(
    private val clickProperties: ClickProperties
) : PaymentGateway {
    override val code: PaymentGatewayCode = PaymentGatewayCode.CLICK

    override fun createCheckout(request: GatewayCheckoutRequest): GatewayCheckoutResult {
        if (!clickProperties.enabled) {
            throw IllegalStateException("Click is disabled (CLICK_ENABLED=false)")
        }
        val secret = clickProperties.secretKey.trim()
        if (secret.isEmpty()) {
            throw IllegalStateException("Click secret key missing (CLICK_SECRET_KEY)")
        }
        if (clickProperties.merchantId <= 0L || clickProperties.serviceId <= 0L) {
            throw IllegalStateException("Click merchant_id / service_id not configured")
        }
        require(request.currency.uppercase() == "UZS") {
            "Click accepts UZS only; got currency=${request.currency}"
        }
        require(request.amountMinor > 0L) {
            "Click amount must be positive"
        }
        val amountParam = request.amountMinor
        val returnUrl = request.successUrl
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Click checkout requires successUrl (return_url)")
        val uri = UriComponentsBuilder.fromUriString(clickProperties.payBaseUrl.trim())
            .queryParam("service_id", clickProperties.serviceId)
            .queryParam("merchant_id", clickProperties.merchantId)
            .queryParam("amount", amountParam)
            .queryParam("transaction_param", request.externalRef)
            .queryParam("return_url", returnUrl)
            .build(true)
            .toUri()

        val url = uri.toASCIIString()

        return GatewayCheckoutResult(
            gatewayPaymentId = request.externalRef,
            checkoutUrl = url,
            status = GatewayCheckoutStatus.PENDING
        )
    }
}
