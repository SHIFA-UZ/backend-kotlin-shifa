package com.shifa.shared

import org.slf4j.MDC

/**
 * Machine-readable error codes (patient-app ask 4). Added next to the existing `error`, `status` and
 * `message` keys; those stay byte-identical so store builds keep working, and `message` strings the app
 * matches on are frozen (FrozenErrorMessagesTest).
 */
enum class ErrorCode {
    VALIDATION, MALFORMED_BODY, BAD_REQUEST, UNAUTHORIZED, PAYMENT_REQUIRED, FORBIDDEN, NOT_FOUND, CONFLICT,
    RATE_LIMITED, SESSION_INVALID, SESSION_EXPIRED, TOKEN_INVALID, ACCOUNT_DISABLED, INTERNAL, UPSTREAM, UNAVAILABLE;

    companion object {
        fun forStatus(status: Int): ErrorCode = when (status) {
            400 -> BAD_REQUEST
            401 -> UNAUTHORIZED
            402 -> PAYMENT_REQUIRED
            403 -> FORBIDDEN
            404 -> NOT_FOUND
            409 -> CONFLICT
            429 -> RATE_LIMITED
            502, 504 -> UPSTREAM
            503 -> UNAVAILABLE
            else -> if (status >= 500) INTERNAL else BAD_REQUEST
        }
    }
}

/** Error body helpers shared by the controller advice and the security filters. */
object ErrorBodies {
    /** The current request id from the MDC (set by RequestIdFilter), or null outside a request. */
    fun requestId(): String? = MDC.get(RequestIdFilter.MDC_KEY)

    /**
     * Standard JSON body: `error` (status), `message`, `status` exactly as before, plus `code` and `requestId`.
     * [extra] adds handler-specific keys (`errors`, `exceptionClass`).
     */
    fun body(status: Int, message: String, code: ErrorCode, extra: Map<String, Any> = emptyMap()): Map<String, Any> {
        val base = linkedMapOf<String, Any>("error" to status, "message" to message, "status" to status, "code" to code.name)
        requestId()?.let { base["requestId"] = it }
        base.putAll(extra)
        return base
    }

    /** For filters that write `{"error":"<text>"}` by hand: same `error` text, plus `code` and `requestId`. */
    fun filterJson(errorText: String, code: ErrorCode): String {
        val rid = requestId()?.let { ""","requestId":"$it"""" } ?: ""
        return """{"error":"$errorText","code":"${code.name}"$rid}"""
    }
}
