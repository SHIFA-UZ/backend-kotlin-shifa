package com.shifa.ai.api

import com.shifa.shared.ErrorCode

/**
 * Thrown when the AI stream cannot continue (rate limit, safety block, or provider error).
 * Controller sends a structured SSE "error" event with code and message, then completes.
 */
class AiStreamException(
    val code: String,
    override val message: String
) : RuntimeException(message) {
    /** The standard name of [code] (BE-08); [code] keeps its legacy value for the store builds. */
    val errorCode: ErrorCode
        get() = when (code) {
            "RATE_LIMIT" -> ErrorCode.AI_QUOTA_EXCEEDED
            "AI_UNAVAILABLE" -> ErrorCode.AI_UPSTREAM
            "SAFETY_BLOCK" -> ErrorCode.AI_SAFETY_BLOCK
            "VALIDATION" -> ErrorCode.VALIDATION
            else -> ErrorCode.INTERNAL
        }

    /** `data` of the SSE `event: error`: `code` and `message` as before, then `errorCode` and `requestId`. */
    fun ssePayload(requestId: String?): Map<String, String> = ssePayload(code, message, errorCode, requestId)

    companion object {
        fun ssePayload(code: String, message: String, errorCode: ErrorCode, requestId: String?): Map<String, String> =
            linkedMapOf("code" to code, "message" to message, "errorCode" to errorCode.name).apply {
                requestId?.let { put("requestId", it) }
            }
    }
}
