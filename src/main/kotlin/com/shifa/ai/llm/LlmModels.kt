package com.shifa.ai.llm

/**
 * Provider-neutral request/response types for chat completions.
 * Business logic (tools, prompts, authorization) stays outside this package.
 */
data class LlmRequest(
    /** OpenAI-style chat messages (role/content[/tool_calls|tool_call_id]). */
    val messages: List<Map<String, Any?>>,
    val tools: List<Map<String, Any?>>? = null,
    val toolChoice: Any? = null,
    val temperature: Double = 0.2,
    val maxTokens: Int = 400,
    /** When set, request `response_format: {type: json_object}`. */
    val jsonObject: Boolean = false,
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>,
)

data class LlmResponse(
    val content: String,
    val toolCalls: List<LlmToolCall>,
    val finishReason: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val providerId: String,
    val model: String,
    val durationMs: Long,
)

enum class LlmErrorKind {
    RATE_LIMITED,
    QUOTA_EXHAUSTED,
    AUTH,
    INVALID_REQUEST,
    UNSUPPORTED_TOOLS,
    TIMEOUT,
    SERVER,
    CONNECTION,
    MALFORMED,
    UNKNOWN,
    ;

    /** Failures where trying the next provider in the chain is reasonable. */
    val retryableOnOtherProvider: Boolean
        get() = this in setOf(
            RATE_LIMITED, QUOTA_EXHAUSTED, TIMEOUT, SERVER, CONNECTION,
            UNSUPPORTED_TOOLS, AUTH, INVALID_REQUEST,
        )

    /** Failures that should temporarily remove the provider from routing. */
    val triggersCooldown: Boolean
        get() = this in setOf(
            RATE_LIMITED, QUOTA_EXHAUSTED, TIMEOUT, SERVER, CONNECTION,
            AUTH, UNSUPPORTED_TOOLS, INVALID_REQUEST,
        )
}

class LlmException(
    val kind: LlmErrorKind,
    val providerId: String,
    val httpStatus: Int? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Why a model call was made; stored with usage for later comparison. */
enum class LlmPurpose {
    TOOL_LOOP,
    SPOKEN_REPLY,
    MEMORY_EXTRACT,
}

/** Deterministic request category used for optional per-category routes. */
enum class RequestCategory {
    SCHEDULE_QUERY,
    PATIENT_SEARCH,
    APPOINTMENT_BOOKING,
    APPOINTMENT_RESCHEDULE,
    APPOINTMENT_CANCELLATION,
    CLINICAL_DOCUMENTATION,
    GENERAL_ASSISTANCE,
}
