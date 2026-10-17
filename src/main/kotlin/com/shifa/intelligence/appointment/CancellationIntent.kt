package com.shifa.intelligence.appointment

/**
 * Keeps a cancellation on the visit the doctor already named or was just shown.
 * "Cancel it" and a misheard surname must not start over with a different patient.
 */
object CancellationIntent {
    private val FILLERS = setOf(
        "man", "men", "maam", "madam", "uh", "um", "please", "mister", "patient",
    )
    private val NOISE = Regex(
        """\b(i|i'm|i am|am|would|like|to|cancel|cancelled|canceled|please|the|a|an|my|me|appointment|appointments|visit|visits|with|him|her|them|for|from|next|this|last|week|one|of|it|that|meant|mean|saying|say|want|wanted)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PERIOD = Regex(
        """\b(today|tomorrow|next week|this week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SHOWN = Regex(
        """\b(it|that one|this one|that visit|this visit|that appointment|this appointment)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val LAST = Regex("""\blast (appointment|visit|one)\b""", RegexOption.IGNORE_CASE)
    private val CANCEL = Regex("""\bcancel\b""", RegexOption.IGNORE_CASE)
    private val NOT_A_NAME = Regex(
        """\b(are you sure|because|should have|how many|why|i think|i thought|wrong|count)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Ordinary words that can survive noise stripping but are never part of a patient name. */
    private val COMMON_WORDS = setOf(
        "what", "when", "where", "who", "whom", "which", "how", "why", "can", "could", "should", "would",
        "will", "shall", "may", "might", "must", "sure", "because", "have", "has", "had", "does", "did",
        "was", "were", "been", "being", "you", "your", "yours", "think", "thought", "know", "maybe",
        "actually", "okay", "yes", "yeah", "nope", "not", "time", "times", "there", "their", "they",
        "these", "those", "then", "than", "about", "again", "also", "and", "but", "all", "any", "some",
        "something", "anything", "nothing", "hello", "hey", "shifa", "doctor", "two", "three", "four",
        "five", "six", "seven", "eight", "nine", "ten", "first", "second", "third", "other", "another",
        "book", "booking", "schedule", "reschedule", "move", "change", "list", "show", "open", "start",
        "complete", "finish", "note", "notes", "signature", "slot", "slots", "free", "available",
    )

    fun shouldPreview(current: String, priorUsers: List<String>, priorAssistants: List<String>): Boolean {
        if (AppointmentActionService.isExplicitConfirmation(current) &&
            AppointmentActionService.actionToken(priorAssistants) != null
        ) {
            return false
        }
        if (CANCEL.containsMatchIn(current)) return true
        if (priorUsers.none { CANCEL.containsMatchIn(it) }) return false
        if (Regex("""\b(list|how many|free slots)\b""", RegexOption.IGNORE_CASE).containsMatchIn(current) &&
            !NOT_A_NAME.containsMatchIn(current)
        ) {
            return false
        }
        if (NOT_A_NAME.containsMatchIn(current)) return true
        return patientName(current) != null || SHOWN.containsMatchIn(current) || LAST.containsMatchIn(current)
    }

    fun isCountQuestion(text: String): Boolean = NOT_A_NAME.containsMatchIn(text)

    fun refersToShown(text: String): Boolean = SHOWN.containsMatchIn(text) || LAST.containsMatchIn(text)

    fun refersToLast(text: String): Boolean = LAST.containsMatchIn(text)

    /** Keep a period named earlier, such as "next week", when the follow-up is only a name or "cancel it". */
    fun rangeText(current: String, priorUsers: List<String>): String {
        if (PERIOD.containsMatchIn(current)) return current
        val earlier = priorUsers.lastOrNull { PERIOD.containsMatchIn(it) } ?: return current
        return "$earlier. $current"
    }

    /**
     * A spoken name is one to three capitalisable words with no verbs, questions, or numbers.
     * Anything longer is a sentence and must never become a roster query.
     */
    fun patientName(text: String): String? {
        if (isCountQuestion(text)) return null
        val stripped = NOISE.replace(text.lowercase(), " ").replace(Regex("""\s+"""), " ").trim()
        val tokens = stripped.split(' ').filter { it.length >= 3 && it !in FILLERS && !PERIOD.containsMatchIn(it) }
        if (tokens.isEmpty() || tokens.size > MAX_NAME_TOKENS) return null
        if (tokens.any { it in COMMON_WORDS }) return null
        if (tokens.any { token -> token.any { !it.isLetter() && it != '\'' && it != '-' } }) return null
        return tokens.joinToString(" ")
    }

    private const val MAX_NAME_TOKENS = 3

    fun nameTokens(query: String): List<String> {
        val normalized = query.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        return normalized.split(Regex("""\s+""")).filter { it.length >= 3 && it !in FILLERS }
    }
}
