package com.shifa.web

import com.fasterxml.jackson.databind.JsonNode
import com.shifa.config.ScribeProperties
import com.shifa.service.ScribePipelineService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Receives Daily.co webhook events (e.g. recording.ready-to-download).
 * No authentication - webhooks are called by Daily.co. Use webhook signature verification in production.
 */
@RestController
@RequestMapping("/api/webhooks/daily")
class DailyWebhookController(
    private val scribePipelineService: ScribePipelineService,
    private val scribeProps: ScribeProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun handleWebhook(@RequestBody body: JsonNode): ResponseEntity<Map<String, String>> {
        // Always return 200 to acknowledge receipt (avoid Daily retries)
        try {
            val type = body.path("type").asText("")
            log.debug("Daily webhook received: type={}", type)

            if (type != "recording.ready-to-download") {
                return ResponseEntity.ok(mapOf("received" to "true", "type" to type))
            }

            val payload = body.path("payload")
            val roomName = payload.path("room_name").asText("")
            val recordingId = payload.path("recording_id").asText("")

            if (roomName.isBlank() || recordingId.isBlank()) {
                log.warn("Webhook missing room_name or recording_id: roomName={}, recordingId={}", roomName, recordingId)
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
