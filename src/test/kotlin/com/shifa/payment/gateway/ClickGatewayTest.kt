package com.shifa.payment.gateway

import com.shifa.config.ClickProperties
import com.shifa.payment.domain.PaymentKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ClickGatewayTest {

    private lateinit var props: ClickProperties
    private lateinit var gateway: ClickGateway

    @BeforeEach
    fun setUp() {
        props = ClickProperties().apply {
            enabled = true
            merchantId = 64374
            serviceId = 111453
            secretKey = "test-secret"
            payBaseUrl = "https://my.click.uz/services/pay"
        }
        gateway = ClickGateway(props)
    }

    @Test
    fun `checkout URL uses soums not ledger minor units`() {
        val result = gateway.createCheckout(
            GatewayCheckoutRequest(
                externalRef = "abc123",
                amountMinor = 5_000_000L, // 50_000.00 UZS in ledger minor
                currency = "UZS",
                kind = PaymentKind.TREATMENT_PLAN,
                description = "bill",
                successUrl = "https://example.com/success",
                cancelUrl = "https://example.com/cancel",
            )
        )
        val uri = java.net.URI(result.checkoutUrl)
        val query = uri.query
        assertTrue(query.contains("amount=50000.0") || query.contains("amount=50000"), query)
        assertTrue(query.contains("transaction_param=abc123"), query)
        assertEquals("CLICK", gateway.code.name)
    }
}
