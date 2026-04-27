package com.shifa.payment.service

import com.shifa.config.StripeProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.payment.domain.PaymentEvent
import com.shifa.payment.domain.PaymentGatewayCode
import com.shifa.payment.repo.PaymentEventRepository
import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Event
import com.stripe.model.Subscription
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class StripeWebhookService(
    private val stripeProperties: StripeProperties,
    private val paymentService: PaymentService,
    private val subscriptionService: SubscriptionService,
    private val paymentEventRepository: PaymentEventRepository,
    private val objectMapper: ObjectMapper
) {
    @Transactional
    fun handleWebhook(payload: String, stripeSignature: String?): Boolean {
        val endpointSecret = stripeProperties.webhookSecret.trim()
        if (endpointSecret.isEmpty() || stripeSignature.isNullOrBlank()) {
            return false
        }

        val event: Event = try {
            Webhook.constructEvent(payload, stripeSignature, endpointSecret)
        } catch (_: SignatureVerificationException) {
            return false
        }

        if (paymentEventRepository.existsByGatewayAndEventId(PaymentGatewayCode.STRIPE, event.id)) {
            return true
        }

        val paymentEvent = paymentEventRepository.save(
            PaymentEvent(
                gateway = PaymentGatewayCode.STRIPE,
                eventId = event.id,
                eventType = event.type,
                payload = payload
            )
        )

        runCatching {
            when (event.type) {
                "checkout.session.completed" -> {
                    val sessionObj = event.dataObjectDeserializer.deserializeUnsafe()
                    val session = sessionObj as? Session
                    if (session != null) {
                        if (session.mode == "subscription") {
                            subscriptionService.activateByStripeCheckoutSession(session)
                        } else {
                            val sessionId = session.id
                            if (!sessionId.isNullOrBlank()) {
                                paymentService.markPaymentPaidByGatewayPaymentId(sessionId)
                            }
                        }
                    }
                }
                "invoice.paid" -> {
                    val subId = extractSubscriptionId(payload)
                    if (!subId.isNullOrBlank()) {
                        subscriptionService.markPaidByStripeSubscriptionId(subId)
                    }
                }
                "invoice.payment_failed" -> {
                    val subId = extractSubscriptionId(payload)
                    if (!subId.isNullOrBlank()) {
                        subscriptionService.markPaymentFailedByStripeSubscriptionId(subId)
                    }
                }
                "customer.subscription.deleted" -> {
                    val subscription = event.dataObjectDeserializer.deserializeUnsafe() as? Subscription
                    if (subscription != null) {
                        subscriptionService.markCancelledByStripeSubscription(subscription)
                    }
                }
            }
            paymentEvent.processed = true
            paymentEvent.processedAt = Instant.now()
            paymentEventRepository.save(paymentEvent)
        }.onFailure { ex ->
            paymentEvent.failureReason = ex.message
            paymentEventRepository.save(paymentEvent)
        }
        return true
    }

    private fun extractSubscriptionId(payload: String): String? {
        return runCatching {
            val root = objectMapper.readTree(payload)
            root.path("data").path("object").path("subscription").asText(null)
        }.getOrNull()
    }
}
