package com.shifa.payment.click

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Mirrors string concatenation + MD5 semantics of the reference Click starter
 * [integration.payment.clickintegration.util.ClickSignatureUtil].
 */
class ClickSignatureUtilTest {
    @Test
    fun `prepare sign matches concatenated payload`() {
        val clickTransId = 555L
        val serviceId = 103972L
        val secret = "test-secret"
        val merchantTransId = "abcd1234"
        val amount = 5000.0
        val action = 0
        val signTime = "2026-01-15 10:30:45"
        val data = "${clickTransId}${serviceId}${secret}${merchantTransId}${amount}${action}${signTime}"
        assertThat(
            ClickSignatureUtil.generatePrepareSignString(
                clickTransId = clickTransId,
                serviceId = serviceId,
                secretKey = secret,
                merchantTransId = merchantTransId,
                amount = amount,
                action = action,
                signTime = signTime
            )
        ).isEqualTo(ClickSignatureUtil.md5Hex(data))
    }

    @Test
    fun `complete sign differs when merchant_prepare_id present`() {
        val base =
            ClickSignatureUtil.generatePrepareSignString(
                clickTransId = 1L,
                serviceId = 2L,
                secretKey = "s",
                merchantTransId = "m",
                amount = 100.0,
                action = 0,
                signTime = "t"
            )
        val complete =
            ClickSignatureUtil.generateCompleteSignString(
                clickTransId = 1L,
                serviceId = 2L,
                secretKey = "s",
                merchantTransId = "m",
                merchantPrepareId = 999L,
                amount = 100.0,
                action = 1,
                signTime = "t"
            )
        assertThat(base).isNotEqualTo(complete)
    }
}
