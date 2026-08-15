package com.shifa.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.config.ScribeProperties
import com.shifa.scribe.DailyWebhookSignature
import com.shifa.service.ScribePipelineService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Receives Daily.co webhook events (e.g. recording.ready-to-download).
 * Real events require a valid HMAC signature. Daily's create/reactivate
 * ping (`{"test":"test"}`) is accepted without a signature so the endpoint
 * can be registered.
 */
@RestController
@RequestMapping("/api/webhooks/daily")
class DailyWebhookController(
    private val scribePipelineService: ScribePipelineService,
    private val scribeProps: ScribeProperties,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun handleWebhook(
        @RequestBody rawBody: String,
        @RequestHeader("X-Webhook-Signature", required = false) signature: String?,
        @RequestHeader("X-Webhook-Timestamp", required = false) timestamp: String?
    ): ResponseEntity<Map<String, String>> {
        val body = runCatching { objectMapper.readTree(rawBody) }.getOrNull()
        if (body != null && DailyWebhookSignature.isEndpointCheck(body)) {
            return ResponseEntity.ok(mapOf("received" to "true", "type" to "test"))
        }

        val secret = scribeProps.dailyWebhookSecret
        if (!DailyWebhookSignature.verify(rawBody, timestamp, signature, secret, Instant.now().epochSecond)) {
            log.warn("Daily webhook rejected: missing or invalid signature")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("received" to "false", "error" to "invalid_signature"))
        }

        // Signed events still return 200 after this point so Daily does not retry/circuit-break.
        try {
            if (body == null) {
                log.warn("Daily webhook rejected after signature check: invalid JSON")
                return ResponseEntity.ok(mapOf("received" to "true", "error" to "invalid_json"))
            }

            val type = body.path("type").asText("")
            log.debug("Daily webhook received: type={}", type)

            if (type != "recording.ready-to-download") {
                return ResponseEntity.ok(mapOf("received" to "true", "type" to type))
            }

            val payload = body.path("payload")
            val roomName = payload.path("room_name").asText("")
            val recordingId = payload.path("recording_id").asText("")

            if (roomName.isBlank() || recordingId.isBlank()) {
                log.warn(
                    "Webhook missing room_name or recording_id: roomName={}, recordingId={}",
                    roomName,
                    recordingId
                )
                return ResponseEntity.ok(mapOf("received" to "true", "error" to "missing_fields"))
            }

            log.info("Triggering scribe pipeline: roomName={}, recordingId={}", roomName, recordingId)
            scribePipelineService.processVideoRecording(roomName, recordingId)

            return ResponseEntity.ok(mapOf("received" to "true", "pipeline" to "started"))
        } catch (e: Exception) {
            log.error("Webhook processing error", e)
            return ResponseEntity.ok(mapOf("received" to "true", "error" to (e.message ?: "unknown")))
        }
    }
}
