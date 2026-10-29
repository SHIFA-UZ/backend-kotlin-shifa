package com.shifa.ai.application

/**
 * A cleanup model must not replace a transcript with a shorter one.
 * Missing speech is reported as missing; it is not reconstructed.
 */
object TranscriptIntegrity {
    fun acceptCleanup(original: String, cleaned: String): String {
        val next = cleaned.trim()
        if (next.isEmpty()) return original
        if (next.length < (original.length * 0.9).toInt()) return original
        return next
    }
}
