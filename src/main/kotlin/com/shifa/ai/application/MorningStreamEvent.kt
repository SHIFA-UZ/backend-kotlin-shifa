package com.shifa.ai.application


/**
 * Morning assistant SSE payloads: spoken tokens and structured tool cards.
 * Tool payloads are read-only proposals; the client confirms writes.
 */
sealed class MorningStreamEvent {
    data class Token(val text: String) : MorningStreamEvent()
    data class Tool(val name: String, val payload: Map<String, Any?>) : MorningStreamEvent()
}
