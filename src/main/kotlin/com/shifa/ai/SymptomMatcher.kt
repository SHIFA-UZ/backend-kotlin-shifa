package com.shifa.ai

/**
 * Multilingual symptom phrase matcher.
 * Maps canonical symptom IDs to phrases in EN, UZ, RU.
 *
 * Example (unit test):
 *   val matches = SymptomMatcher.findMatches("Patient has chest pain")
 *   assert(matches.any { it.symptomId == "chest_pain" })
 */
object SymptomMatcher {

    /** One matched occurrence of a symptom phrase in the text. */
    data class MatchedSymptom(
        val symptomId: String,
        val tokenIndex: Int,
        val phrase: String
    )

    private const val NEGATION_WINDOW = 5

    /**
     * Canonical symptom ID -> list of phrases (any language).
     * Order longer phrases first so "chest pain" is matched before "pain".
     */
    private val SYMPTOM_PHRASES: Map<String, List<String>> = mapOf(
        // Chest / cardiac
        "chest_pain" to listOf(
            "chest pain", "pressure in chest", "chest pressure",
            "ko'krak og'rig'i", "ko'krakdagi og'riq",
            "боль в груди", "давление в груди", "боли в грудной клетке"
        ),
        "shortness_of_breath" to listOf(
            "shortness of breath", "difficulty breathing", "can't breathe",
            "nafas qisilishi", "nafas olish qiyin",
            "одышка", "затрудненное дыхание", "тяжело дышать"
        ),
        // Neurological
        "stroke" to listOf(
            "stroke", "brain attack", "cerebrovascular",
            "falaj", "insult",
            "инсульт", "мозговой удар"
        ),
        "slurred_speech" to listOf(
            "slurred speech", "speech difficulty",
            "nutq buzilishi", "gapirishda qiyinchilik",
            "невнятная речь", "нарушение речи"
        ),
        "weakness" to listOf(
            "weakness", "one-sided weakness", "limb weakness", "generalized weakness",
            "zaiflik", "bir tomondan zaiflik",
            "слабость", "односторонняя слабость", "слабость в конечностях"
        ),
        "loss_of_consciousness" to listOf(
            "loss of consciousness", "passed out", "unconscious", "fainted", "syncope",
            "ongdan ketish", "hushini yo'qotish", "bezgak",
            "потеря сознания", "обморок", "без сознания", "потерял сознание"
        ),
        "seizure" to listOf(
            "seizure", "seizures", "convulsion", "fitting",
            "tutqanoq", "konvulsiya",
            "судороги", "судорожный приступ", "припадок", "конвульсии"
        ),
        "sudden_confusion" to listOf(
            "sudden confusion", "confusion", "disorientation", "altered mental status",
            "tushunarsizlik", "ong o'zgarishi",
            "внезапная спутанность", "спутанность сознания", "дезориентация"
        ),
        // Bleeding
        "bleeding" to listOf(
            "bleeding", "severe bleeding", "uncontrolled bleeding", "haemorrhage", "hemorrhage",
            "qon ketish", "qon oqimi", "og'ir qon ketish",
            "кровотечение", "кровопотеря", "сильное кровотечение"
        ),
        "vomiting_blood" to listOf(
            "vomiting blood", "vomited blood", "haematemesis", "hematemesis",
            "qon qusish", "qusishda qon",
            "рвота кровью", "кровавая рвота"
        ),
        "blood_in_stool" to listOf(
            "blood in stool", "rectal bleeding",
            "najasda qon", "rektal qon ketish",
            "кровь в стуле", "ректальное кровотечение"
        ),
        // Pregnancy
        "pregnancy" to listOf(
            "pregnant", "pregnancy", "expecting",
            "homilador", "homiladorlik", "homilador ayol",
            "беременная", "беременность", "беременности"
        ),
        "pregnancy_bleeding" to listOf(
            "pregnancy bleeding", "bleeding in pregnancy", "vaginal bleeding pregnant",
            "homiladorlikda qon ketish", "homilador ayolda qon",
            "кровотечение при беременности", "кровотечение у беременной"
        ),
        // Mental health / danger
        "suicidal" to listOf(
            "suicidal", "suicide", "suicidal thoughts", "want to die", "end my life",
            "o'z joniga qasd qilish", "intilishi", "o'limni xohlaydi",
            "суицид", "суицидальные мысли", "хочет умереть"
        ),
        "suicidal_plan" to listOf(
            "has a plan", "has plan", "specific plan", "when and how",
            "rejasi bor", "qanday qilish rejasi",
            "есть план", "конкретный план", "знает как"
        ),
        "self_harm" to listOf(
            "self harm", "self-harm", "cutting", "self injury",
            "o'ziga zarar", "jismoniy zarar",
            "самоповреждение", "нанес себе вред"
        ),
        // Abdominal / other
        "severe_abdominal_pain" to listOf(
            "severe abdominal pain", "acute abdomen", "uncontrolled abdominal pain",
            "qorin og'rig'i", "og'ir qorin og'rig'i",
            "острая боль в животе", "сильная боль в животе"
        )
    ).mapValues { (_, phrases) -> phrases.sortedByDescending { it.length } }

    /**
     * Finds all symptom phrase matches in [text].
     * Returns matches with the token index of the start of each phrase (for negation/amplifier window).
     */
    fun findMatches(text: String): List<MatchedSymptom> {
        if (text.isBlank()) return emptyList()
        val lower = text.lowercase()
        val tokens = tokenize(lower)
        val results = mutableListOf<MatchedSymptom>()
        val usedSpans = mutableSetOf<IntRange>() // avoid double-counting overlapping phrases

        for ((symptomId, phrases) in SYMPTOM_PHRASES) {
            for (phrase in phrases) {
                if (phrase.isBlank()) continue
                var start = 0
                while (true) {
                    val idx = lower.indexOf(phrase, start, ignoreCase = true)
                    if (idx < 0) break
                    val span = idx until (idx + phrase.length)
                    if (usedSpans.none { it.overlaps(span) }) {
                        val tokenIndex = tokenIndexForCharOffset(lower, idx, tokens)
                        results.add(MatchedSymptom(symptomId, tokenIndex, phrase.trim()))
                        usedSpans.add(span)
                    }
                    start = idx + 1
                }
            }
        }
        return results.distinctBy { "${it.symptomId}_${it.tokenIndex}" }
    }

    private fun tokenize(text: String): List<String> =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun tokenIndexForCharOffset(text: String, charOffset: Int, tokens: List<String>): Int {
        val prefix = text.substring(0, charOffset.coerceIn(0, text.length))
        return prefix.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
    }

    private fun IntRange.overlaps(other: IntRange): Boolean =
        first < other.last && last > other.first

    fun getSymptomIds(): Set<String> = SYMPTOM_PHRASES.keys.toSet()
}
