package com.shifa.ai.domain

import java.time.OffsetDateTime

enum class IntelligenceMemoryType {
    DOCTOR_PREFERENCE,
    WORKFLOW_PREFERENCE,
    CLINIC_CONVENTION,
    TERMINOLOGY,
    TEMPORARY_CONTEXT,
}

enum class IntelligenceMemoryScope { DOCTOR, CLINIC }

enum class IntelligenceMemoryStatus { ACTIVE, SUPERSEDED, DELETED }

data class IntelligenceMemory(
    val id: Long,
    val scope: IntelligenceMemoryScope,
    val ownerDoctorId: Long?,
    val clinicId: Long?,
    val type: IntelligenceMemoryType,
    val content: String,
    val contentKey: String?,
    val confidence: Double,
    val status: IntelligenceMemoryStatus,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime?,
)

/** Structured candidate produced by extraction, before validation. */
data class IntelligenceMemoryCandidate(
    val type: IntelligenceMemoryType,
    val content: String,
    val contentKey: String?,
    val confidence: Double,
    val temporary: Boolean,
)
