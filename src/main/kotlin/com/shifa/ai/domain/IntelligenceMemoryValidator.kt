package com.shifa.ai.domain

/**
 * Pure rules deciding whether an extracted candidate may be persisted.
 * Rejects anything that looks like patient data, identifiers, instructions, or low confidence.
 */
object IntelligenceMemoryValidator {
    const val MAX_CONTENT_CHARS = 240
    const val MAX_CANDIDATES_PER_TURN = 3

    private val phoneOrIdLike = Regex("""\d[\d\s\-()]{6,}\d""")
    private val longDigitRun = Regex("""\d{5,}""")
    private val instructionLike = Regex(
        """(?i)\b(ignore|disregard|override|system prompt|you are now|always call|never call|execute|run the tool|delete all|cancel all)\b""",
    )
    private val patientDataLike = Regex(
        """(?i)\b(diagnos|prescri|medication|dose|mg\b|blood pressure|hiv|hepatit|pregnan|allerg|icd-?10|passport|phone number)""",
    )

    data class Rejection(val reason: String)

    fun validate(
        candidate: IntelligenceMemoryCandidate,
        minConfidence: Double,
        blockedNames: Collection<String> = emptyList(),
    ): Rejection? {
        val content = candidate.content.trim()
        if (content.length < 8) return Rejection("too_short")
        if (content.length > MAX_CONTENT_CHARS) return Rejection("too_long")
        if (candidate.confidence < minConfidence) return Rejection("low_confidence")
        if (phoneOrIdLike.containsMatchIn(content) || longDigitRun.containsMatchIn(content)) return Rejection("identifier_like")
        if (instructionLike.containsMatchIn(content)) return Rejection("instruction_like")
        if (patientDataLike.containsMatchIn(content)) return Rejection("patient_data_like")
        val lower = content.lowercase()
        for (name in blockedNames) {
            val parts = name.lowercase().split(Regex("""\s+""")).filter { it.length >= 3 }
            if (parts.any { lower.contains(it) }) return Rejection("contains_patient_name")
        }
        if (candidate.type == IntelligenceMemoryType.CLINIC_CONVENTION) return Rejection("clinic_scope_creation_deferred")
        return null
    }

    /** Stable key for supersede logic: lowercase, alnum + underscore, max 96 chars. */
    fun normalizeKey(raw: String?): String? {
        val k = raw?.trim()?.lowercase()?.replace(Regex("""[^a-z0-9]+"""), "_")?.trim('_') ?: return null
        return k.takeIf { it.length in 2..96 }
    }
}
