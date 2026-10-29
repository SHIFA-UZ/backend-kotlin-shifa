package com.shifa.appointments.domain

import com.shifa.appointments.application.AppointmentActionService

/**
 * Interprets a short follow-up against an active pending patient question.
 * Confirmation stems only count when a pending question exists at the call site.
 */
object PendingPatientAnswer {
    enum class Kind { CONFIRM, REJECT, CORRECT, UNRELATED, AMBIGUOUS }

    data class Result(
        val kind: Kind,
        val correctionName: String? = null,
    ) {
        val turnType: BookingTurnType = when (kind) {
            Kind.CONFIRM -> BookingTurnType.CONFIRM
            Kind.REJECT -> BookingTurnType.REJECT
            Kind.CORRECT -> BookingTurnType.CORRECT_SLOT
            Kind.UNRELATED -> BookingTurnType.UNRELATED_QUERY
            Kind.AMBIGUOUS -> BookingTurnType.AMBIGUOUS
        }
    }

    fun classify(text: String): Result {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return Result(Kind.AMBIGUOUS)
        if (UNRELATED.containsMatchIn(normalized)) return Result(Kind.UNRELATED)
        if (AppointmentActionService.isExplicitConfirmation(normalized)) return Result(Kind.CONFIRM)
        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        val first = tokens.first()
        if (first in CONFIRM_STEMS) {
            val rest = tokens.drop(1).filter { it !in FILLER }
            return if (rest.isEmpty() || rest.all { it in FILLER }) Result(Kind.CONFIRM)
            else if (looksLikeName(rest)) Result(Kind.CORRECT, rest.joinToString(" "))
            else Result(Kind.CONFIRM)
        }
        if (first in REJECT_STEMS) return Result(Kind.REJECT)
        if (looksLikeName(tokens)) return Result(Kind.CORRECT, tokens.joinToString(" "))
        return Result(Kind.AMBIGUOUS)
    }

    private fun looksLikeName(tokens: List<String>): Boolean {
        if (tokens.isEmpty() || tokens.size > 4) return false
        if (tokens.any { CLOCK.containsMatchIn(it) || it in NOT_A_NAME }) return false
        return tokens.all { token -> token.any { it.isLetter() } }
    }

    private fun normalize(text: String): String =
        text.trim().lowercase()
            .replace(Regex("""[''ʻ’`]"""), "'")
            .replace(Regex("""[,.!?]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private val CONFIRM_STEMS = setOf(
        "ha", "haa", "yes", "yeah", "yep", "ok", "okay", "da", "да", "ja",
        "xo'p", "xop", "mayli", "confirm", "proceed",
        "to'g'ri", "togri", "richtig", "genau", "именно", "exactly", "aynan",
    )
    private val FILLER = setOf(
        "shunday", "aynan", "exactly", "genau", "именно", "richtig", "shu",
        "albatta", "please", "definitely", "thanks", "rahmat",
        "tasdiqlayman", "to'g'ri", "togri", "correct", "right", "that", "is", "so",
    )
    private val REJECT_STEMS = setOf("no", "nope", "yo'q", "yoq", "нет", "not", "emas", "nah")
    private val NOT_A_NAME = setOf(
        "today", "tomorrow", "yesterday", "schedule", "calendar", "appointment",
        "bugun", "ertaga", "сегодня", "завтра",
    )
    private val CLOCK = Regex("""\d{1,2}:\d{2}""")
    private val UNRELATED = Regex(
        """(?:\bcancel\b|bekor|отмен|reschedul|move (the |my |this |that )?(appointment|visit)|""" +
            """ko['ʼʻ''`]?chir|kochir|o['ʼʻ''`]?tkaz|otkaz|перенес|""" +
            """today'?s schedule|who is (next|coming)|bugungi (qabul|jadval)|""" +
            """free slot|bo'sh joy|document|dental)""",
        RegexOption.IGNORE_CASE,
    )
}
