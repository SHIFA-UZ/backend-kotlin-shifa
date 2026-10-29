package com.shifa.ai.domain

data class MedicalCodeSuggestion(
    val system: CodingSystem,
    val code: String,
    val term: String,
    val confidence: Double,
    val note: String
)
