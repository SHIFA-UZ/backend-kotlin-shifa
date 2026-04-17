package com.shifa.ai

/**
 * Detects negations within a token window before a symptom mention.
 * If a symptom is negated (e.g. "no chest pain"), it should not trigger.
 *
 * Example (unit test):
 *   val matches = listOf(SymptomMatcher.MatchedSymptom("chest_pain", 3, "chest pain"))
 *   val tokens = "patient no chest pain today".split(" ")
 *   assert(NegationDetector.isNegated("chest_pain", 2, tokens))
 */
object NegationDetector {

    private const val WINDOW_SIZE = 5

    private val NEGATION_WORDS = setOf(
        // English
        "no", "not", "denies", "deny", "without", "never", "none", "neither",
        "no longer", "doesn't", "don't", "didn't", "hasn't", "haven't", "hadn't",
        "isn't", "aren't", "wasn't", "weren't", "won't", "wouldn't", "couldn't",
        // Uzbek
        "yo'q", "yoq", "inkor", "inkor qiladi", "emas", "emas",
        // Russian
        "нет", "не", "отрицает", "без", "никогда", "ни"
    ).map { it.lowercase() }.toSet()

    /**
     * Returns true if the symptom at [tokenIndex] is preceded (within [WINDOW_SIZE] tokens)
     * by a negation word in [tokens].
     */
    fun isNegated(tokenIndex: Int, tokens: List<String>): Boolean {
        val start = (tokenIndex - WINDOW_SIZE).coerceAtLeast(0)
        for (i in start until tokenIndex) {
            if (i in tokens.indices && tokens[i].lowercase() in NEGATION_WORDS) return true
            // Check two-word negation (e.g. "no longer")
            if (i + 1 in tokens.indices) {
                val twoWord = "${tokens[i].lowercase()} ${tokens[i + 1].lowercase()}"
                if (twoWord in NEGATION_WORDS) return true
            }
        }
        return false
    }

    /**
     * Filters [matches] to only those that are NOT negated in [text].
     */
    fun filterNegated(text: String, matches: List<SymptomMatcher.MatchedSymptom>): List<SymptomMatcher.MatchedSymptom> {
        val tokens = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return matches.filter { !isNegated(it.tokenIndex, tokens) }
    }
}
