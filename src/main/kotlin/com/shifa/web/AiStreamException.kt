package com.shifa.web

/**
 * Thrown when the AI stream cannot continue (rate limit, safety block, or provider error).
 * Controller sends a structured SSE "error" event with code and message, then completes.
 */
class AiStreamException(
    val code: String,
    override val message: String
) : RuntimeException(message)
