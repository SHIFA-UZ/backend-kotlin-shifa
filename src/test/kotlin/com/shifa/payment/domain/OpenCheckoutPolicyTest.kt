package com.shifa.payment.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class OpenCheckoutPolicyTest {

    private val now = Instant.parse("2026-10-28T12:00:00Z")

    private fun payment(
        status: PaymentStatus = PaymentStatus.PENDING,
        gateway: PaymentGatewayCode = PaymentGatewayCode.CLICK,
        ageMinutes: Long = 2,
        checkoutUrl: String? = "https://pay.example/x",
    ) = Payment(
        externalRef = "ref",
        gateway = gateway,
        kind = PaymentKind.CONSULTATION,
        status = status,
        amountMinor = 300_000,
        currency = "UZS",
        gatewayCheckoutUrl = checkoutUrl,
        createdAt = now.minusSeconds(ageMinutes * 60),
    )

    @Test
    fun `fresh open checkout on the requested gateway is reused`() {
        assertTrue(OpenCheckoutPolicy.reusable(payment(), PaymentGatewayCode.CLICK, now, holdMinutes = 15))
    }

    @Test
    fun `no gateway preference reuses whatever is open`() {
        assertTrue(OpenCheckoutPolicy.reusable(payment(gateway = PaymentGatewayCode.STRIPE), null, now, 15))
    }

    @Test
    fun `other gateway is not reused`() {
        assertFalse(OpenCheckoutPolicy.reusable(payment(gateway = PaymentGatewayCode.STRIPE), PaymentGatewayCode.CLICK, now, 15))
    }

    @Test
    fun `older than the hold window is not reused`() {
        assertFalse(OpenCheckoutPolicy.reusable(payment(ageMinutes = 16), PaymentGatewayCode.CLICK, now, 15))
        assertTrue(OpenCheckoutPolicy.reusable(payment(ageMinutes = 14), PaymentGatewayCode.CLICK, now, 15))
    }

    @Test
    fun `missing checkout url or closed status is not reused`() {
        assertFalse(OpenCheckoutPolicy.reusable(payment(checkoutUrl = ""), null, now, 15))
        assertFalse(OpenCheckoutPolicy.reusable(payment(status = PaymentStatus.FAILED), null, now, 15))
        assertFalse(OpenCheckoutPolicy.reusable(payment(status = PaymentStatus.PAID), null, now, 15))
    }
}
