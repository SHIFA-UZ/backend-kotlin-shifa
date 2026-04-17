package com.shifa.ai

/**
 * Contextual triage engine for medical red-flag detection.
 * Supports English, Uzbek, and Russian; negation detection; severity amplifiers; and symptom combinations.
 *
 * Example (unit test):
 *   val r = RedFlagEngine.analyze("No chest pain but severe headache")
 *   assert(!r.hasEmergency)
 *   assert(r.matchedFlags.any { it.contains("headache").not() }) // chest_pain negated
 *
 *   val r2 = RedFlagEngine.analyze("Sudden chest pain and shortness of breath")
 *   assert(r2.hasEmergency)
 *   assert(r2.severity == SeverityLevel.CRITICAL)
 *
 *   val r3 = RedFlagEngine.analyze("Patient denies suicidal thoughts")
 *   assert(!r3.hasEmergency)
 *
 *   val r4 = RedFlagEngine.analyze("Homilador ayolda qon ketish")
 *   assert(r4.hasEmergency) // pregnancy + bleeding (Uzbek)
 *
 *   val r5 = RedFlagEngine.analyze("Пациент с судорогами и потерей сознания")
 *   assert(r5.hasEmergency) // seizure + loss of consciousness (Russian)
 */
object RedFlagEngine {

    fun analyze(text: String): RedFlagResult {
        if (text.isBlank()) {
            return RedFlagResult(
                hasEmergency = false,
                severity = SeverityLevel.NONE,
                matchedFlags = emptyList(),
                reasoning = "No text to analyze.",
                confidence = 0.0
            )
        }

        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        // 1. Find all symptom matches (multilingual)
        val allMatches = SymptomMatcher.findMatches(trimmed)

        // 2. Remove negated matches
        val matchesAfterNegation = NegationDetector.filterNegated(trimmed, allMatches)

        // 3. Which remaining matches have an amplifier nearby?
        val amplifiedIds = AmplifierDetector.findAmplified(trimmed, matchesAfterNegation)

        // 4. Check for dangerous combinations
        val symptomIds = matchesAfterNegation.map { it.symptomId }.toSet()
        val combinationResult = CombinationRuleEngine.evaluate(symptomIds)

        // 5. Score severity and confidence
        val scoreResult = SeverityScorer.score(matchesAfterNegation, amplifiedIds, combinationResult)

        // 6. Build output
        val matchedFlags = matchesAfterNegation.map { it.symptomId }.distinct()
        val hasEmergency = scoreResult.severity == SeverityLevel.CRITICAL ||
            scoreResult.severity == SeverityLevel.HIGH

        val reasoning = buildReasoning(
            matchedFlags = matchedFlags,
            amplifiedIds = amplifiedIds,
            combinationResult = combinationResult,
            scoreResult = scoreResult,
            negatedCount = allMatches.size - matchesAfterNegation.size
        )

        return RedFlagResult(
            hasEmergency = hasEmergency,
            severity = scoreResult.severity,
            matchedFlags = matchedFlags,
            reasoning = reasoning,
            confidence = scoreResult.confidence
        )
    }

    private fun buildReasoning(
        matchedFlags: List<String>,
        amplifiedIds: Set<String>,
        combinationResult: CombinationRuleEngine.CombinationResult?,
        scoreResult: SeverityScorer.ScoreResult,
        negatedCount: Int
    ): String {
        val parts = mutableListOf<String>()
        parts.add(scoreResult.reasoning)
        if (matchedFlags.isNotEmpty()) {
            parts.add("Matched: ${matchedFlags.joinToString(", ")}.")
        }
        if (amplifiedIds.isNotEmpty()) {
            parts.add("Amplifiers near: ${amplifiedIds.joinToString(", ")}.")
        }
        combinationResult?.let {
            parts.add("Combination: ${it.description}")
        }
        if (negatedCount > 0) {
            parts.add("$negatedCount mention(s) excluded due to negation.")
        }
        return parts.joinToString(" ")
    }
}

/*
 * Unit test examples (expected behaviour):
 *
 * 1) "No chest pain but severe headache"
 *    -> chest_pain matched but NEGATED (no/denies within 5 tokens); headache not in red-flag list.
 *    -> hasEmergency = false, severity = NONE or LOW, matchedFlags empty or non-emergency.
 *
 * 2) "Sudden chest pain and shortness of breath"
 *    -> chest_pain + shortness_of_breath + amplifier "sudden" -> combination rule fires.
 *    -> hasEmergency = true, severity = CRITICAL, confidence >= 0.9.
 *
 * 3) "Patient denies suicidal thoughts"
 *    -> suicidal matched but NEGATED (denies).
 *    -> hasEmergency = false, severity = NONE.
 *
 * 4) "Homilador ayolda qon ketish" (Uzbek: pregnant woman bleeding)
 *    -> pregnancy + bleeding (qon ketish) -> combination.
 *    -> hasEmergency = true, severity = CRITICAL.
 *
 * 5) "Пациент с судорогами и потерей сознания" (Russian: patient with seizures and loss of consciousness)
 *    -> seizure (судороги) + loss_of_consciousness (потерей сознания) -> combination.
 *    -> hasEmergency = true, severity = HIGH or CRITICAL.
 */
