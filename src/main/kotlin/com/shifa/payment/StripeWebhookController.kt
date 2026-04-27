package com.shifa.payment

import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Event
import com.stripe.net.Webhook
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Receives and processes Stripe webhook events.
 *
 * Stripe sends a `Stripe-Signature` header with every request so the payload
 * can be verified against [stripeWebhookSecret] before any business logic runs.
 * The raw request body is read as bytes to preserve the exact bytes that Stripe
 * signed — any re-serialisation would break the HMAC check.
 *
 * Endpoint: POST /api/webhooks/stripe
 * Auth: none (Stripe calls this directly; security is provided by signature verification)
 *
 * Configure the webhook secret in your Stripe Dashboard under
 * Developers → Webhooks → [your endpoint] → Signing secret, then set the
 * STRIPE_WEBHOOK_SECRET environment variable.
 */
@RestController
@RequestMapping("/api/webhooks/stripe")
class StripeWebhookController(
    private val stripeWebhookService: StripeWebhookService,
    @Value("\${stripe.webhook-secret:}") private val stripeWebhookSecret: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun handleWebhook(request: HttpServletRequest): ResponseEntity<Map<String, String>> {
        // Read the raw body bytes — Stripe's signature is computed over the exact payload bytes.
        val payload = runCatching { request.inputStream.readBytes().toString(Charsets.UTF_8) }
            .getOrElse {
                log.error("Stripe webhook: failed to read request body", it)
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(mapOf("error" to "Could not read request body"))
            }

        val sigHeader = request.getHeader("Stripe-Signature")
            ?: run {
                log.warn("Stripe webhook: missing Stripe-Signature header")
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(mapOf("error" to "Missing Stripe-Signature header"))
            }

        // Reject requests when no webhook secret is configured — this prevents
        // accidentally processing unverified events in misconfigured environments.
        if (stripeWebhookSecret.isBlank()) {
            log.error("Stripe webhook: STRIPE_WEBHOOK_SECRET is not configured — rejecting request")
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Webhook secret not configured"))
        }

        // Verify the signature. Stripe uses HMAC-SHA256 with a 5-minute tolerance window.
        val event: Event = try {
            Webhook.constructEvent(payload, sigHeader, stripeWebhookSecret)
        } catch (e: SignatureVerificationException) {
            log.warn("Stripe webhook: invalid signature — {}", e.message)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Invalid webhook signature"))
        } catch (e: Exception) {
            log.error("Stripe webhook: error constructing event", e)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Malformed webhook payload"))
        }

        log.debug("Stripe webhook received: type={}, id={}", event.type, event.id)

        // Dispatch to the service layer based on event type.
        try {
            when (event.type) {
                "payment_intent.succeeded"      -> stripeWebhookService.handlePaymentIntentSucceeded(event)
                "payment_intent.payment_failed" -> stripeWebhookService.handlePaymentIntentFailed(event)
                "charge.refunded"               -> stripeWebhookService.handleChargeRefunded(event)
                else -> log.debug("Stripe webhook: unhandled event type={}", event.type)
            }
        } catch (e: Exception) {
            // Log but still return 200 — Stripe will retry on non-2xx responses, which could
            // cause duplicate processing. Failures should be handled asynchronously.
            log.error("Stripe webhook: error processing event type={}, id={}", event.type, event.id, e)
        }

        return ResponseEntity.ok(mapOf("received" to "true", "type" to event.type))
    }
}
