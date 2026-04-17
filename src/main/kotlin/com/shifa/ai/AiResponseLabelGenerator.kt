package com.shifa.ai

/**
 * Generates a short label (max ~5 words) for an AI response for use as draft note title.
 * Extracts from first non-empty line; no LLM call to keep latency and cost low.
 */
object AiResponseLabelGenerator {

    private const val MAX_WORDS = 5
    private const val MAX_LENGTH = 100
    private const val DEFAULT_LABEL = "AI consultation draft"

    fun generateLabel(aiResponseText: String): String {
        if (aiResponseText.isBlank()) return DEFAULT_LABEL
        val firstLine = aiResponseText.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return DEFAULT_LABEL
        val words = firstLine.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(MAX_WORDS)
        val label = words.joinToString(" ")
        return if (label.length > MAX_LENGTH) label.take(MAX_LENGTH).trim() else label
    }
}
