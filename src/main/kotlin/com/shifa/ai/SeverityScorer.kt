package com.shifa.ai

/**
 * Assigns severity level and confidence from matched symptoms, amplifiers, and combinations.
 *
 * Rules:
 * - Single weak symptom → LOW, 0.5
 * - Amplified symptom → MODERATE/HIGH, 0.7
 * - Combination → HIGH/CRITICAL, 0.9+
 */
object SeverityScorer {

    data class ScoreResult(
        val severity: SeverityLevel,
        val confidence: Double,
        val reasoning: String
    )

    /** Symptom IDs that are inherently high-severity even alone (e.g. suicidal, pregnancy_bleeding). */
    private val HIGH_SEVERITY_ALONE = setOf(
        "suicidal", "suicidal_plan", "self_harm", "pregnancy_bleeding",
        "vomiting_blood", "loss_of_consciousness"
    )

    fun score(
        matchedAfterNegation: List<SymptomMatcher.MatchedSymptom>,
        amplifiedSymptomIds: Set<String>,
        combinationResult: CombinationRuleEngine.CombinationResult?
    ): ScoreResult {
        val symptomIds = matchedAfterNegation.map { it.symptomId }.toSet()

        if (symptomIds.isEmpty()) {
            return ScoreResult(
                severity = SeverityLevel.NONE,
                confidence = 0.0,
                reasoning = "No red-flag symptoms detected."
            )
        }

        // Combination overrides
        if (combinationResult != null) {
            val severity = combinationResult.severityBoost
            val confidence = when (severity) {
                SeverityLevel.CRITICAL -> 0.95
                SeverityLevel.HIGH -> 0.9
                else -> 0.85
            }
            return ScoreResult(
                severity = severity,
                confidence = confidence,
                reasoning = "Dangerous combination: ${combinationResult.description}."
            )
        }

        // Single or multiple symptoms, no combination rule
        val hasHighAlone = symptomIds.any { it in HIGH_SEVERITY_ALONE }
        val hasAmplified = symptomIds.any { it in amplifiedSymptomIds }
        val count = symptomIds.size

        return when {
            hasHighAlone && hasAmplified -> ScoreResult(
                severity = SeverityLevel.HIGH,
                confidence = 0.85,
                reasoning = "High-severity symptom with severity amplifier."
            )
            hasHighAlone -> ScoreResult(
                severity = SeverityLevel.HIGH,
                confidence = 0.75,
                reasoning = "High-severity symptom detected."
            )
            hasAmplified && count >= 1 -> ScoreResult(
                severity = SeverityLevel.MODERATE,
                confidence = 0.7,
                reasoning = "Symptom(s) with severity amplifier (e.g. severe, sudden)."
            )
            count >= 2 -> ScoreResult(
                severity = SeverityLevel.MODERATE,
                confidence = 0.6,
                reasoning = "Multiple symptoms mentioned."
            )
            else -> ScoreResult(
                severity = SeverityLevel.LOW,
                confidence = 0.5,
                reasoning = "Single symptom mentioned; consider clinical context."
            )
        }
    }
}
