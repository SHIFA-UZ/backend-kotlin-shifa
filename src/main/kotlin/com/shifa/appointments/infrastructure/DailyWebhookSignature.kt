package com.shifa.appointments.infrastructure

import tools.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies Daily.co webhook HMAC signatures.
 *
 * Daily signs `X-Webhook-Timestamp + "." + rawBody` with HMAC-SHA256 using a
 * Base64-decoded shared secret, then Base64-encodes the digest as `X-Webhook-Signature`.
 *
 * @see <a href="https://docs.daily.co/reference/rest-api/webhooks">Daily webhooks</a>
 */
object DailyWebhookSignature {
    const val MAX_AGE_SECONDS: Long = 300L

    fun isEndpointCheck(body: JsonNode): Boolean {
        return body.path("test").asText("") == "test" &&
            !body.has("type") &&
            !body.has("payload")
    }

    fun verify(
        rawBody: String,
        timestampHeader: String?,
        signatureHeader: String?,
        base64HmacSecret: String,
        nowEpochSeconds: Long = Instant.now().epochSecond
    ): Boolean {
        val secret = base64HmacSecret.trim()
        val timestamp = timestampHeader?.trim().orEmpty()
        val signature = signatureHeader?.trim().orEmpty()
        if (secret.isEmpty() || timestamp.isEmpty() || signature.isEmpty()) {
            return false
        }

        val eventEpochSeconds = parseEpochSeconds(timestamp) ?: return false
        if (kotlin.math.abs(nowEpochSeconds - eventEpochSeconds) > MAX_AGE_SECONDS) {
            return false
        }

        val secretBytes = try {
            Base64.getDecoder().decode(secret)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (secretBytes.isEmpty()) {
            return false
        }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        val computed = Base64.getEncoder()
            .encode(mac.doFinal("$timestamp.$rawBody".toByteArray(StandardCharsets.UTF_8)))
        val provided = signature.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(computed, provided)
    }

    internal fun parseEpochSeconds(timestamp: String): Long? {
        timestamp.toLongOrNull()?.let { numeric ->
            return if (numeric > 10_000_000_000L) numeric / 1000L else numeric
        }
        return runCatching { Instant.parse(timestamp).epochSecond }.getOrNull()
    }
}
