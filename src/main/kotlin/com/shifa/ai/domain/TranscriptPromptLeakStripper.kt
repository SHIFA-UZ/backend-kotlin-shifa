package com.shifa.ai.domain

/**
 * Removes STT prompt-echo: long comma-separated medical glossaries that were never spoken.
 */
object TranscriptPromptLeakStripper {
    private val commaList = Regex(
        """(?:[\p{L}0-9'ʼʻ’.\-/]{1,40}(?:,|;)\s+){9,}[\p{L}0-9'ʼʻ’.\-/]{1,40}""",
        RegexOption.IGNORE_CASE,
    )

    fun strip(transcript: String): String {
        if (transcript.isBlank() || transcript == "(No speech detected)") return transcript
        val withoutLists = commaList.replace(transcript, " ")
        val sentences = withoutLists
            .split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isGlossaryResidue(it) }
        return sentences.joinToString(" ").replace(Regex("\\s{2,}"), " ").trim()
            .ifBlank { transcript.trim() }
    }

    private fun isGlossaryResidue(sentence: String): Boolean {
        val commas = sentence.count { it == ',' }
        val words = sentence.split(Regex("\\s+")).count { it.isNotBlank() }
        if (words >= 8 && commas >= 6 && commas * 2 >= words) return true
        val lower = sentence.lowercase()
        val glossaryHits = listOf(
            "quloq-burn-tomoq",
            "laboratoriya his",
            "rentgenografiya rentgen",
            "epidural blok",
            "otolaryngologist",
            "phthisiatrician",
        ).count { lower.contains(it) }
        return glossaryHits >= 2
    }
}
