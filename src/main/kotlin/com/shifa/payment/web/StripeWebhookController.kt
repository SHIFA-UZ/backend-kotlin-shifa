package com.shifa.payment.web

import com.shifa.payment.service.StripeWebhookService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/webhooks/stripe")
class StripeWebhookController(
    private val stripeWebhookService: StripeWebhookService
) {
    @PostMapping
    fun handleStripeWebhook(
        @RequestBody payload: String,
        @RequestHeader("Stripe-Signature", required = false) stripeSignature: String?
    ): ResponseEntity<Map<String, Any>> {
        val accepted = stripeWebhookService.handleWebhook(payload, stripeSignature)
        return if (accepted) {
            ResponseEntity.ok(mapOf("received" to true))
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("received" to false))
        }
    }
}
