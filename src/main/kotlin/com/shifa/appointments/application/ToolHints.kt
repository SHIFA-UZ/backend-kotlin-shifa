package com.shifa.appointments.application

/**
 * Machine-readable twin of a tool event's English `spokenHint` (BE-07): `hint: {code, params}` lets the app word the
 * sentence in its own language. `spokenHint` stays as it was for store builds. Codes are listed in
 * docs/api/ai-stream-codes.md; params hold ids, counts, names and ISO dates/instants, never English text.
 */
object ToolHints {
    fun of(code: String, params: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf("code" to code, "params" to params)
}
