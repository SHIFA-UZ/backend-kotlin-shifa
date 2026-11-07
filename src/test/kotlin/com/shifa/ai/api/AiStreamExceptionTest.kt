package com.shifa.ai.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** BE-08: the SSE error keeps `code` and `message` as before and adds `errorCode` and `requestId`. */
class AiStreamExceptionTest {
    @Test
    fun `legacy codes keep their value and gain the standard name`() {
        val payload = AiStreamException("RATE_LIMIT", "AI rate limit exceeded.").ssePayload("req-12345678")
        assertEquals(listOf("code", "message", "errorCode", "requestId"), payload.keys.toList())
        assertEquals("RATE_LIMIT", payload["code"])
        assertEquals("AI_QUOTA_EXCEEDED", payload["errorCode"])
        assertEquals("req-12345678", payload["requestId"])

        assertEquals("AI_UPSTREAM", AiStreamException("AI_UNAVAILABLE", "x").ssePayload(null)["errorCode"])
        assertEquals("AI_SAFETY_BLOCK", AiStreamException("SAFETY_BLOCK", "x").ssePayload(null)["errorCode"])
        assertEquals("VALIDATION", AiStreamException("VALIDATION", "x").ssePayload(null)["errorCode"])
        assertEquals(3, AiStreamException("VALIDATION", "x").ssePayload(null).size) // no requestId outside a request
    }
}
