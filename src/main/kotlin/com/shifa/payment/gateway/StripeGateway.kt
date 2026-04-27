package com.shifa.payment.gateway

import com.shifa.config.StripeProperties
import com.shifa.payment.domain.PaymentGatewayCode
import com.stripe.Stripe
import com.stripe.param.checkout.SessionCreateParams
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData.ProductData
import com.stripe.param.checkout.SessionCreateParams.PaymentIntentData
import com.stripe.param.checkout.SessionCreateParams.PaymentMethodType
import com.stripe.param.checkout.SessionCreateParams.SavedPaymentMethodOptions
import com.stripe.model.checkout.Session
import org.springframework.stereotype.Component

@Component
class StripeGateway(
    private val stripeProperties: StripeProperties
) : PaymentGateway {
    override val code: PaymentGatewayCode = PaymentGatewayCode.STRIPE

    override fun createCheckout(request: GatewayCheckoutRequest): GatewayCheckoutResult {
        val apiKey = stripeProperties.secretKey.trim()
        require(apiKey.isNotEmpty()) { "Stripe is not configured (missing STRIPE_SECRET_KEY)" }
        Stripe.apiKey = apiKey

        val lineItem = SessionCreateParams.LineItem.builder()
            .setQuantity(1)
            .setPriceData(
                PriceData.builder()
                    .setCurrency(request.currency.lowercase())
                    .setUnitAmount(request.amountMinor)
                    .setProductData(
                        ProductData.builder()
                            .setName(request.description)
                            .build()
                    )
                    .build()
            )
            .build()

        val builder = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setClientReferenceId(request.externalRef)
            .putMetadata("externalRef", request.externalRef)
            .addLineItem(lineItem)
            .setSuccessUrl(request.successUrl ?: "https://example.com/payment-success")
            .setCancelUrl(request.cancelUrl ?: "https://example.com/payment-cancel")
            .addPaymentMethodType(PaymentMethodType.CARD)
            .addPaymentMethodType(PaymentMethodType.PAYPAL)
            .setSavedPaymentMethodOptions(
                SavedPaymentMethodOptions.builder().setPaymentMethodSave(SavedPaymentMethodOptions.PaymentMethodSave.ENABLED).build()
            )

        if (!request.destinationAccountId.isNullOrBlank()) {
            builder.setPaymentIntentData(
                PaymentIntentData.builder()
                    .setTransferData(
                        PaymentIntentData.TransferData.builder()
                            .setDestination(request.destinationAccountId)
                            .build()
                    )
                    .build()
            )
        }

        val session = Session.create(builder.build())
        return GatewayCheckoutResult(
            gatewayPaymentId = session.id,
            checkoutUrl = session.url ?: "",
            status = GatewayCheckoutStatus.PENDING
        )
    }
}
