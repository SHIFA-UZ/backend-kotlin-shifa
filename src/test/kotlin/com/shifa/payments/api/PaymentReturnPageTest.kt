package com.shifa.payments.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaymentReturnPageTest {
    @Test
    fun `deep link carries payment id, reference and status, url-encoded`() {
        assertEquals(
            "shifa://payment/return?paymentId=42&ref=abc%26x&status=success",
            PaymentController.appReturnUrl(42, "abc&x", "success"),
        )
        assertEquals("shifa://payment/return?status=cancel", PaymentController.appReturnUrl(null, null, "cancel"))
    }

    @Test
    fun `reference is html-escaped on the page`() {
        assertEquals("&lt;b&gt;&amp;&quot;", PaymentController.escapeHtml("<b>&\""))
    }
}
