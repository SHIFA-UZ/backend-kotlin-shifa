package com.shifa.ai

/**
 * Evaluates dangerous symptom combinations and returns severity boost.
 * If a combination is detected, severity is escalated.
 *
 * Example (unit test):
 *   val matched = setOf("chest_pain", "shortness_of_breath")
 *   val result = CombinationRuleEngine.evaluate(matched)
 *   assert(result != null && result.severityBoost == SeverityLevel.CRITICAL)
 */
object CombinationRuleEngine {

    data class CombinationResult(
        val matchedSymptomIds: Set<String>,
        val severityBoost: SeverityLevel,
        val description: String
    )

    /**
     * Combination rules: set of symptom IDs -> severity boost and description.
     * Order matters for first-match; put more specific (larger) sets first if needed.
     */
    private val COMBINATION_RULES: List<Pair<Set<String>, CombinationResult>> = listOf(
        setOf("chest_pain", "shortness_of_breath") to CombinationResult(
            matchedSymptomIds = setOf("chest_pain", "shortness_of_breath"),
            severityBoost = SeverityLevel.CRITICAL,
            description = "Chest pain with shortness of breath (possible cardiac/PE)"
        ),
        setOf("slurred_speech", "weakness") to CombinationResult(
            matchedSymptomIds = setOf("slurred_speech", "weakness"),
            severityBoost = SeverityLevel.CRITICAL,
            description = "Slurred speech with weakness (possible stroke)"
        ),
        setOf("pregnancy", "bleeding") to CombinationResult(
            matchedSymptomIds = setOf("pregnancy", "bleeding"),
            severityBoost = SeverityLevel.CRITICAL,
            description = "Pregnancy with bleeding"
        ),
        setOf("pregnancy", "pregnancy_bleeding") to CombinationResult(
            matchedSymptomIds = setOf("pregnancy", "pregnancy_bleeding"),
            severityBoost = SeverityLevel.CRITICAL,
            description = "Pregnancy with bleeding"
        ),
        setOf("suicidal", "suicidal_plan") to CombinationResult(
            matchedSymptomIds = setOf("suicidal", "suicidal_plan"),
            severityBoost = SeverityLevel.CRITICAL,
            description = "Suicidal ideation with plan"
        ),
        setOf("suicidal", "self_harm") to CombinationResult(
            matchedSymptomIds = setOf("suicidal", "self_harm"),
            severityBoost = SeverityLevel.HIGH,
            description = "Suicidal or self-harm indicators"
        ),
        setOf("loss_of_consciousness", "seizure") to CombinationResult(
            matchedSymptomIds = setOf("loss_of_consciousness", "seizure"),
            severityBoost = SeverityLevel.HIGH,
            description = "Loss of consciousness with seizure"
        ),
        setOf("stroke", "weakness") to CombinationResult(
            matchedSymptomIds = setOf("stroke", "weakness"),
            severityBoost = SeverityLevel.CRITICAL,
            description = "Stroke with weakness"
        )
    )

    /**
     * Returns the first matching combination rule, or null if no combination matches.
     */
    fun evaluate(matchedSymptomIds: Set<String>): CombinationResult? {
        if (matchedSymptomIds.size < 2) return null
        for ((symptomSet, result) in COMBINATION_RULES) {
            if (symptomSet.all { it in matchedSymptomIds }) return result
        }
        return null
    }
}
