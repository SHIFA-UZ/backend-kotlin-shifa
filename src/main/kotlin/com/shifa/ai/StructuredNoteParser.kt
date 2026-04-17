package com.shifa.ai

/**
 * Parses AI response text for structured sections and maps to SOAP-style fields.
 * Section headers (case-insensitive): INITIAL ASSESSMENT, POSSIBLE CAUSES, QUESTIONS, NEXT STEPS, etc.
 */
object StructuredNoteParser {

    data class ParsedNote(
        val subjective: String?,
        val assessment: String?,
        val plan: String?,
        val body: String?
    )

    private val SECTION_TO_FIELD = mapOf(
        "INITIAL ASSESSMENT" to "assessment",
        "ASSESSMENT" to "assessment",
        "POSSIBLE CAUSES" to "assessment",
        "SUBJECTIVE" to "subjective",
        "OBJECTIVE" to "assessment",
        "PLAN" to "plan",
        "NEXT STEPS" to "plan",
        "QUESTIONS" to "plan",
        "RECOMMENDATIONS" to "plan"
    )

    fun parse(fullText: String): ParsedNote {
        if (fullText.isBlank()) return ParsedNote(null, null, null, fullText.trim())
        val lines = fullText.lines()
        val sections = mutableMapOf<String, MutableList<String>>()
        var currentField: String? = null
        for (line in lines) {
            val trimmed = line.trim()
            val headerMatch = SECTION_TO_FIELD.entries.firstOrNull { (header) ->
                trimmed.equals(header, ignoreCase = true) ||
                    trimmed.replace(Regex("^[*#]+\\s*"), "").startsWith(header, ignoreCase = true) ||
                    trimmed.equals("$header:", ignoreCase = true)
            }
            if (headerMatch != null) {
                currentField = headerMatch.value
                sections.getOrPut(currentField) { mutableListOf() }.clear()
                val afterHeader = trimmed.removePrefix(headerMatch.key).removePrefix(":").trim()
                if (afterHeader.isNotEmpty()) sections.getOrPut(currentField) { mutableListOf() }.add(afterHeader)
            } else if (currentField != null && trimmed.isNotEmpty()) {
                sections.getOrPut(currentField) { mutableListOf() }.add(trimmed)
            }
        }
        val subjective = sections["subjective"]?.joinToString("\n")?.trim()?.ifBlank { null }
        val assessment = sections["assessment"]?.joinToString("\n")?.trim()?.ifBlank { null }
        val plan = sections["plan"]?.joinToString("\n")?.trim()?.ifBlank { null }
        val hasStructured = subjective != null || assessment != null || plan != null
        val body = if (hasStructured) null else fullText.trim()
        return ParsedNote(subjective = subjective, assessment = assessment, plan = plan, body = body)
    }
}
