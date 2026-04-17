package com.shifa.ai

/**
 * Structured result of contextual red-flag triage analysis.
 *
 * Example (unit test):
 *   val r = RedFlagEngine.analyze("Sudden chest pain and shortness of breath")
 *   assert(r.hasEmergency)
 *   assert(r.severity == SeverityLevel.CRITICAL)
 *   assert(r.matchedFlags.any { it.contains("chest") })
 */
data class RedFlagResult(
    val hasEmergency: Boolean,
    val severity: SeverityLevel,
    val matchedFlags: List<String>,
    val reasoning: String,
    val confidence: Double
)

enum class SeverityLevel {
    NONE,
    LOW,
    MODERATE,
    HIGH,
    CRITICAL
}
