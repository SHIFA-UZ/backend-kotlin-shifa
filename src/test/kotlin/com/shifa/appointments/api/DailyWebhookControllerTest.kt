package com.shifa.appointments.api

import tools.jackson.databind.ObjectMapper
import com.shifa.shared.config.ScribeProperties
import com.shifa.ai.application.ScribePipelineService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.http.HttpStatus
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class DailyWebhookControllerTest {
    private val secret = "NQrSA5z0FkJ44QPrFerW7uCc5kdNLv3l2FDEKDanL1U="
    private val rawBody =
        """{"type":"recording.ready-to-download","payload":{"room_name":"appointment-42","recording_id":"rec-9"}}"""

    @Test
    fun `endpoint check is accepted without a signature`() {
        val pipeline = mock(ScribePipelineService::class.java)
        val response = controller(pipeline).handleWebhook("""{"test":"test"}""", null, null)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("test", response.body?.get("type"))
        verify(pipeline, never()).processVideoRecording(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `valid signature starts the scribe pipeline`() {
        val pipeline = mock(ScribePipelineService::class.java)
        val timestamp = Instant.now().epochSecond.toString()
        val response = controller(pipeline).handleWebhook(rawBody, sign(timestamp, rawBody), timestamp)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("started", response.body?.get("pipeline"))
        verify(pipeline).processVideoRecording("appointment-42", "rec-9")
    }

    @Test
    fun `invalid signature is rejected`() {
        val pipeline = mock(ScribePipelineService::class.java)
        val timestamp = Instant.now().epochSecond.toString()
        val response = controller(pipeline).handleWebhook(rawBody, "not-a-valid-signature", timestamp)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        verify(pipeline, never()).processVideoRecording(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())
    }

    private fun controller(pipeline: ScribePipelineService): DailyWebhookController {
        val props = ScribeProperties().apply { dailyWebhookSecret = secret }
        return DailyWebhookController(pipeline, props, ObjectMapper())
    }

    private fun sign(timestamp: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256"))
        return Base64.getEncoder()
            .encodeToString(mac.doFinal("$timestamp.$body".toByteArray(StandardCharsets.UTF_8)))
    }
}
