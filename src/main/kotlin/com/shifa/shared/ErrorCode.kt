package com.shifa.shared

import com.shifa.shared.api.RequestIdFilter
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Machine-readable error codes (patient-app ask 4). Added next to the existing `error`, `status` and
 * `message` keys; those stay byte-identical so store builds keep working, and `message` strings the app
 * matches on are frozen (FrozenErrorMessagesTest).
 */
enum class ErrorCode {
    VALIDATION, MALFORMED_BODY, BAD_REQUEST, UNAUTHORIZED, PAYMENT_REQUIRED, FORBIDDEN, NOT_FOUND, CONFLICT,
    RATE_LIMITED, SESSION_INVALID, SESSION_EXPIRED, TOKEN_INVALID, ACCOUNT_DISABLED, INTERNAL, UPSTREAM, UNAVAILABLE,

    // Thrown as [CodedStatusException] where the status alone doesn't tell the app what to do.
    /** Registration: the account is already a doctor; sign in instead. */
    DOCTOR_EXISTS,
    /** Registration onto an existing account: prove it is yours (its e-mail code or its current password). */
    OWNERSHIP_REQUIRED,
    EMAIL_OTP_INVALID,
    EMAIL_OTP_EXPIRED,
    /** Account deletion: resolve the listed obligations first (`details`). */
    HAS_ACTIVE_OBLIGATIONS,
    /** No-show: only for a confirmed or started visit, from its start until 24 h after its end. */
    NO_SHOW_NOT_ALLOWED,
    /** The doctor has no early-partner contract. */
    NO_CONTRACT,
    /** Video token: the join window has not opened yet (`joinWindow` in the body). */
    VIDEO_TOO_EARLY,
    /** Video token: the join window has closed (`joinWindow` in the body). */
    VIDEO_TOO_LATE,
    /** Video token: the visit is paid and the payment is not confirmed yet. */
    VIDEO_PAYMENT_REQUIRED,
    /** Idempotency-Key: not 8 to 128 letters, digits, '-' or '_'. */
    IDEMPOTENCY_KEY_INVALID,
    /** Idempotency-Key: already used for another path or body by this account. */
    IDEMPOTENCY_KEY_REUSED,
    /** Idempotency-Key: the first request with this key is still running; retry shortly. */
    IDEMPOTENCY_IN_PROGRESS,
    /** Treatment-plan compose: the installments do not add up to the outstanding amount (`outstandingMinor`, `scheduledMinor`). */
    INSTALLMENTS_MISMATCH,

    // AI (BE-08): `errorCode` of an SSE `event: error`, and `code` of the doctor streams' refusals before they open.
    /** The account's AI limit (daily or burst) is used up; retry later. Legacy stream code `RATE_LIMIT`. */
    AI_QUOTA_EXCEEDED,
    /** The AI provider failed or is unavailable. Legacy stream code `AI_UNAVAILABLE`. */
    AI_UPSTREAM,
    /** The subscription tier does not include this AI feature (403). */
    AI_TIER_REQUIRED,
    /** The request or answer was blocked by the safety rules. Legacy stream code `SAFETY_BLOCK`. */
    AI_SAFETY_BLOCK;

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

/**
 * A [ResponseStatusException] that names its own [code] (and optional [details]) instead of the status's
 * default; the controller advice puts both in the body. [extra] adds structured keys to the body (e.g. `joinWindow`);
 * it never replaces a standard key.
 */
class CodedStatusException(
    status: HttpStatus,
    reason: String,
    val code: ErrorCode,
    val details: List<String> = emptyList(),
    val extra: Map<String, Any> = emptyMap(),
) : ResponseStatusException(status, reason)
