package com.shifa.ai

/**
 * Detects severity amplifiers (e.g. "severe", "sudden") within a token window before a symptom.
 * Amplifier + symptom increases severity and confidence.
 *
 * Example (unit test):
 *   val tokens = "patient has sudden chest pain".split(" ")
 *   assert(AmplifierDetector.hasAmplifierBefore(2, tokens))
 */
object AmplifierDetector {

    private const val WINDOW_SIZE = 5

    private val AMPLIFIER_WORDS = setOf(
        // English
        "severe", "sudden", "acute", "worsening", "uncontrollable", "massive",
        "extreme", "critical", "intense", "violent", "sharp", "excruciating",
        "rapid", "progressive", "persistent", "constant", "unbearable",
        // Uzbek
        "og'ir", "to'satdan", "keskin", "kuchayib", "boshqarib bo'lmaydigan",
        "zo'r", "shiddatli", "keskin", "yomonlashuv",
        // Russian
        "сильный", "сильная", "сильное", "внезапный", "острая", "острое",
        "ухудшающийся", "неконтролируемый", "массивный", "критический",
        "интенсивный", "резкий", "невыносимый", "постоянный"
    ).map { it.lowercase() }.toSet()

    /**
     * Returns true if there is an amplifier word within [WINDOW_SIZE] tokens before [tokenIndex].
     */
    fun hasAmplifierBefore(tokenIndex: Int, tokens: List<String>): Boolean {
        val start = (tokenIndex - WINDOW_SIZE).coerceAtLeast(0)
        for (i in start until tokenIndex) {
            if (i in tokens.indices && tokens[i].lowercase() in AMPLIFIER_WORDS) return true
        }
        return false
    }

    /**
     * Returns the set of symptom IDs from [matches] that have an amplifier in [text] before them.
     */
    fun findAmplified(text: String, matches: List<SymptomMatcher.MatchedSymptom>): Set<String> {
        val tokens = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return matches.filter { hasAmplifierBefore(it.tokenIndex, tokens) }.map { it.symptomId }.toSet()
    }
}
