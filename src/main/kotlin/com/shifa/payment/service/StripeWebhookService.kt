package com.shifa.payment.service

import com.shifa.config.StripeProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.payment.domain.PaymentEvent
import com.shifa.payment.domain.PaymentGatewayCode
import com.shifa.payment.repo.PaymentEventRepository
import com.stripe.exception.SignatureVerificationException
import com.stripe.Stripe
import com.stripe.model.Event
import com.stripe.model.Subscription
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@Service
class StripeWebhookService(
    private val stripeProperties: StripeProperties,
    private val paymentService: PaymentService,
    private val subscriptionService: SubscriptionService,
    private val paymentEventRepository: PaymentEventRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(StripeWebhookService::class.java)

    @Transactional
    fun handleWebhook(payload: String, stripeSignature: String?): Boolean {
        val endpointSecret = stripeProperties.webhookSecret.trim()
        if (endpointSecret.isEmpty() || stripeSignature.isNullOrBlank()) {
            log.warn("Stripe webhook rejected: missing webhook secret or signature.")
            return false
        }

        val event: Event = try {
            Webhook.constructEvent(payload, stripeSignature, endpointSecret)
        } catch (_: SignatureVerificationException) {
            log.warn("Stripe webhook rejected: signature verification failed.")
            return false
        }

        if (paymentEventRepository.existsByGatewayAndEventId(PaymentGatewayCode.STRIPE, event.id)) {
            log.info("Stripe webhook duplicate ignored: eventId={}, type={}", event.id, event.type)
            return true
        }

        log.info("Stripe webhook received: eventId={}, type={}", event.id, event.type)

        val paymentEvent = paymentEventRepository.save(
            PaymentEvent(
                gateway = PaymentGatewayCode.STRIPE,
                eventId = event.id,
                eventType = event.type,
                payload = payload
            )
        )

        runCatching {
            processStripeEvent(event.id, event.type, payload)
            paymentEvent.processed = true
            paymentEvent.processedAt = Instant.now()
            paymentEvent.failureReason = null
            paymentEventRepository.save(paymentEvent)
            log.info("Stripe webhook processed successfully: eventId={}, type={}", event.id, event.type)
        }.onFailure { ex ->
            paymentEvent.failureReason = ex.message
            paymentEventRepository.save(paymentEvent)
            log.error(
                "Stripe webhook processing failed: eventId={}, type={}, reason={}",
                event.id,
                event.type,
                ex.message,
                ex
            )
        }
        return true
    }

    @Transactional
    fun retryStoredWebhookEvent(paymentEventId: Long, adminUserId: Long?): Boolean {
        val paymentEvent = paymentEventRepository.findById(paymentEventId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Payment event not found") }
        if (paymentEvent.gateway != PaymentGatewayCode.STRIPE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Only Stripe webhook events can be retried")
        }
        log.info(
            "Retrying stored Stripe webhook event: id={}, eventId={}, type={}, processed={}, hasFailure={}",
            paymentEvent.id,
            paymentEvent.eventId,
            paymentEvent.eventType,
            paymentEvent.processed,
            !paymentEvent.failureReason.isNullOrBlank()
        )

        return runCatching {
            processStripeEvent(paymentEvent.eventId, paymentEvent.eventType, paymentEvent.payload)
            paymentEvent.processed = true
            paymentEvent.processedAt = Instant.now()
            paymentEvent.failureReason = null
            paymentEvent.retryCount += 1
            paymentEvent.lastRetryAt = Instant.now()
            paymentEvent.retriedByAdminUserId = adminUserId
            paymentEventRepository.save(paymentEvent)
            log.info(
                "Stored Stripe webhook event retried successfully: id={}, eventId={}, type={}, retriedByAdminUserId={}, retryCount={}",
                paymentEvent.id,
                paymentEvent.eventId,
                paymentEvent.eventType,
                adminUserId,
                paymentEvent.retryCount
            )
            true
        }.getOrElse { ex ->
            paymentEvent.failureReason = ex.message
            paymentEvent.retryCount += 1
            paymentEvent.lastRetryAt = Instant.now()
            paymentEvent.retriedByAdminUserId = adminUserId
            paymentEventRepository.save(paymentEvent)
            log.error(
                "Stored Stripe webhook event retry failed: id={}, eventId={}, type={}, retriedByAdminUserId={}, retryCount={}, reason={}",
                paymentEvent.id,
                paymentEvent.eventId,
                paymentEvent.eventType,
                adminUserId,
                paymentEvent.retryCount,
                ex.message,
                ex
            )
            false
        }
    }

    private fun processStripeEvent(eventId: String, eventType: String, payload: String) {
        val root = objectMapper.readTree(payload)
        when (eventType) {
            "checkout.session.completed" -> {
                val sessionId = root.path("data").path("object").path("id").asText(null)
                val mode = root.path("data").path("object").path("mode").asText(null)
                log.info(
                    "Stripe checkout.session.completed: eventId={}, mode={}, sessionId={}",
                    eventId,
                    mode,
                    sessionId
                )
                if (sessionId.isNullOrBlank()) {
                    log.warn("Stripe checkout.session.completed missing sessionId: eventId={}", eventId)
                    return
                }
                if (mode == "subscription") {
                    val session = withStripeApiKey { Session.retrieve(sessionId) }
                    subscriptionService.activateByStripeCheckoutSession(session)
                    log.info(
                        "Stripe subscription activation triggered by checkout session: eventId={}, sessionId={}",
                        eventId,
                        sessionId
                    )
                } else {
                    val payment = paymentService.markPaymentPaidByGatewayPaymentId(sessionId)
                    log.info(
                        "Stripe consultation payment marked paid: eventId={}, sessionId={}, paymentId={}, externalRef={}",
                        eventId,
                        sessionId,
                        payment?.id,
                        payment?.externalRef
                    )
                }
            }
            "invoice.paid" -> {
                val subId = extractSubscriptionId(payload)
                if (!subId.isNullOrBlank()) {
                    subscriptionService.markPaidByStripeSubscriptionId(subId)
                    log.info(
                        "Stripe subscription invoice.paid applied: eventId={}, subscriptionId={}",
                        eventId,
                        subId
                    )
                } else {
                    log.warn("Stripe invoice.paid missing subscription id: eventId={}", eventId)
                }
            }
            "invoice.payment_failed" -> {
                val subId = extractSubscriptionId(payload)
                if (!subId.isNullOrBlank()) {
                    subscriptionService.markPaymentFailedByStripeSubscriptionId(subId)
                    log.info(
                        "Stripe subscription invoice.payment_failed applied: eventId={}, subscriptionId={}",
                        eventId,
                        subId
                    )
                } else {
                    log.warn("Stripe invoice.payment_failed missing subscription id: eventId={}", eventId)
                }
            }
            "customer.subscription.deleted" -> {
                val subId = root.path("data").path("object").path("id").asText(null)
                if (subId.isNullOrBlank()) {
                    log.warn("Stripe customer.subscription.deleted missing subscription id: eventId={}", eventId)
                    return
                }
                val subscription = withStripeApiKey { Subscription.retrieve(subId) }
                subscriptionService.markCancelledByStripeSubscription(subscription)
                log.info(
                    "Stripe customer.subscription.deleted applied: eventId={}, subscriptionId={}",
                    eventId,
                    subscription.id
                )
            }
            else -> {
                log.info("Stripe webhook event ignored by handler: eventId={}, type={}", eventId, eventType)
            }
        }
    }

    private fun <T> withStripeApiKey(call: () -> T): T {
        val apiKey = stripeProperties.secretKey.trim()
        if (apiKey.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe is not configured")
        }
        Stripe.apiKey = apiKey
        return call()
    }

    private fun extractSubscriptionId(payload: String): String? {
        return runCatching {
            val root = objectMapper.readTree(payload)
            root.path("data").path("object").path("subscription").asText(null)
        }.getOrNull()
    }
}
