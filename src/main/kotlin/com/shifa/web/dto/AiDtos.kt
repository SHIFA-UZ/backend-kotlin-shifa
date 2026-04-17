package com.shifa.web.dto

data class AiAskRequest(
    val prompt: String,
    val mode: String? = null // optional: e.g., "schedule", "summary", etc.
)

data class AiAskResponse(
    val answer: String,
    val followUps: List<String> = emptyList()
)
