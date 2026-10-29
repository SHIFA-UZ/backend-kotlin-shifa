package com.shifa.ai.domain

data class ClinicalRagSearchHit(
    val id: Long,
    val sourceType: String,
    val contentText: String,
    /** Cosine distance per pgvector `<=>` (lower is closer). */
    val distance: Double,
)
