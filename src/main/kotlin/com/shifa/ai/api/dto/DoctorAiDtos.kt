package com.shifa.ai.api.dto

import com.shifa.ai.domain.OutputLanguage

data class SpeakRequest(
    val text: String,
    val language: OutputLanguage = OutputLanguage.UZ,
)

data class CommitVisitFactsRequest(
    val appointmentId: Long,
    val acceptFactIds: List<Long>? = null,
    val rejectFactIds: List<Long>? = null,
)
