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

    fun shouldPreview(current: String, priorUsers: List<String>, priorAssistants: List<String>): Boolean {
        if (AppointmentActionService.isExplicitConfirmation(current) &&
            AppointmentActionService.actionToken(priorAssistants) != null
        ) {
            return false
        }
        if (CANCEL.containsMatchIn(current)) return true
        if (priorUsers.none { CANCEL.containsMatchIn(it) }) return false
        if (Regex("""\b(list|how many|free slots)\b""", RegexOption.IGNORE_CASE).containsMatchIn(current)) {
            return false
        }
        return patientName(current) != null || SHOWN.containsMatchIn(current) || LAST.containsMatchIn(current)
    }

    fun refersToShown(text: String): Boolean = SHOWN.containsMatchIn(text) || LAST.containsMatchIn(text)

    fun refersToLast(text: String): Boolean = LAST.containsMatchIn(text)

    /** Keep a period named earlier, such as "next week", when the follow-up is only a name or "cancel it". */
    fun rangeText(current: String, priorUsers: List<String>): String {
        if (PERIOD.containsMatchIn(current)) return current
        val earlier = priorUsers.lastOrNull { PERIOD.containsMatchIn(it) } ?: return current
        return "$earlier. $current"
    }

    fun patientName(text: String): String? {
        val stripped = NOISE.replace(text.lowercase(), " ").replace(Regex("""\s+"""), " ").trim()
        val tokens = stripped.split(' ').filter { it.length >= 3 && it !in FILLERS && !PERIOD.containsMatchIn(it) }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ")
    }

    fun nameTokens(query: String): List<String> {
        val normalized = query.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        return normalized.split(Regex("""\s+""")).filter { it.length >= 3 && it !in FILLERS }
    }
}
