package com.shifa.payment.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.config.StripeProperties
import com.shifa.payment.repo.PaymentEventRepository
import com.stripe.net.Webhook
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import com.shifa.payment.domain.PaymentEvent
import com.shifa.payment.domain.PaymentGatewayCode

class StripeWebhookServiceTest {
    private val secret = "whsec_test_secret"
    private val props = StripeProperties().apply { webhookSecret = secret }
    private val payments = mock(PaymentService::class.java)
    private val subscriptions = mock(SubscriptionService::class.java)
    private val events = mock(PaymentEventRepository::class.java)
    private val service = StripeWebhookService(props, payments, subscriptions, events, ObjectMapper())

    private val payload = """
        {"id":"evt_1","object":"event","type":"checkout.session.completed","data":{"object":{"id":"cs_1","object":"checkout.session","mode":"payment"}}}
    """.trim()

    @Test
    fun `invalid signature is rejected`() {
        assertFalse(service.handleWebhook(payload, "t=1,v1=bad"))
    }

    @Test
    fun `success is applied once and a duplicate event is ignored`() {
        val timestamp = Webhook.Util.getTimeNow()
        val signatureHash = Webhook.Util.computeHmacSha256(secret, "$timestamp.$payload")
        val signature = "t=$timestamp,v1=$signatureHash"
        `when`(events.existsByGatewayAndEventId(PaymentGatewayCode.STRIPE, "evt_1"))
            .thenReturn(false, true)
        `when`(
            events.save(
                any(PaymentEvent::class.java) ?: PaymentEvent(
                    gateway = PaymentGatewayCode.STRIPE,
                    eventId = "evt_1",
                    eventType = "checkout.session.completed",
                    payload = payload,
                ),
            ),
        ).thenAnswer { it.arguments[0] }

        assertTrue(service.handleWebhook(payload, signature))
        assertTrue(service.handleWebhook(payload, signature))

        verify(payments, times(1)).markPaymentPaidByGatewayPaymentId("cs_1")
    }
}
