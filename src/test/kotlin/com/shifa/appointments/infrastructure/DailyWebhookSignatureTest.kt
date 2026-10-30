package com.shifa.appointments.infrastructure

import tools.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class DailyWebhookSignatureTest {
    private val secret = "NQrSA5z0FkJ44QPrFerW7uCc5kdNLv3l2FDEKDanL1U="
    private val now = 1_700_000_000L
    private val rawBody = """{"type":"recording.ready-to-download","payload":{"room_name":"appointment-1","recording_id":"rec-1"}}"""

    @Test
    fun `accepts a valid Daily HMAC signature`() {
        val timestamp = now.toString()
        assertTrue(
            DailyWebhookSignature.verify(
                rawBody = rawBody,
                timestampHeader = timestamp,
                signatureHeader = sign(timestamp, rawBody, secret),
                base64HmacSecret = secret,
                nowEpochSeconds = now
            )
        )
    }

    @Test
    fun `rejects a tampered body`() {
        val timestamp = now.toString()
        val signature = sign(timestamp, rawBody, secret)
        assertFalse(
            DailyWebhookSignature.verify(
                rawBody = rawBody.replace("rec-1", "rec-forged"),
                timestampHeader = timestamp,
                signatureHeader = signature,
                base64HmacSecret = secret,
                nowEpochSeconds = now
            )
        )
    }

    @Test
    fun `rejects a missing or blank signature`() {
        val timestamp = now.toString()
        assertFalse(
            DailyWebhookSignature.verify(rawBody, timestamp, null, secret, now)
        )
        assertFalse(
            DailyWebhookSignature.verify(rawBody, timestamp, "  ", secret, now)
        )
    }

    @Test
    fun `rejects an empty secret`() {
        val timestamp = now.toString()
        assertFalse(
            DailyWebhookSignature.verify(
                rawBody = rawBody,
                timestampHeader = timestamp,
                signatureHeader = sign(timestamp, rawBody, secret),
                base64HmacSecret = "",
                nowEpochSeconds = now
            )
        )
    }

    @Test
    fun `rejects a stale timestamp`() {
        val timestamp = (now - DailyWebhookSignature.MAX_AGE_SECONDS - 1).toString()
        assertFalse(
            DailyWebhookSignature.verify(
                rawBody = rawBody,
                timestampHeader = timestamp,
                signatureHeader = sign(timestamp, rawBody, secret),
                base64HmacSecret = secret,
                nowEpochSeconds = now
            )
        )
    }

    @Test
    fun `accepts a timestamp within the allowed window`() {
        val timestamp = (now - DailyWebhookSignature.MAX_AGE_SECONDS).toString()
        assertTrue(
            DailyWebhookSignature.verify(
                rawBody = rawBody,
                timestampHeader = timestamp,
                signatureHeader = sign(timestamp, rawBody, secret),
                base64HmacSecret = secret,
                nowEpochSeconds = now
            )
        )
    }

    @Test
    fun `detects Daily endpoint check payload`() {
        val mapper = ObjectMapper()
        assertTrue(DailyWebhookSignature.isEndpointCheck(mapper.readTree("""{"test":"test"}""")))
        assertFalse(DailyWebhookSignature.isEndpointCheck(mapper.readTree(rawBody)))
    }

    private fun sign(timestamp: String, body: String, base64Secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(base64Secret), "HmacSHA256"))
        return Base64.getEncoder()
            .encodeToString(mac.doFinal("$timestamp.$body".toByteArray(StandardCharsets.UTF_8)))
    }
}
