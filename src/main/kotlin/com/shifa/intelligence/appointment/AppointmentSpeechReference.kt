package com.shifa.intelligence.appointment

import java.time.LocalDate
import java.time.LocalTime

/**
 * Pulls patient / source / destination slots out of a spoken reschedule turn.
 * Used so Kotlin can resolve a trusted schedule row even when the LLM omits IDs.
 */
object AppointmentSpeechReference {
    data class Parsed(
        val patientName: String?,
        val sourceDate: LocalDate?,
        val sourceTime: LocalTime?,
        val destinationDate: LocalDate?,
        val destinationTime: LocalTime?,
    )

    fun parse(text: String, today: LocalDate): Parsed {
        val clocks = CLOCK.findAll(text).mapNotNull { toTime(it) }.toList()
        val moving = MOVE.containsMatchIn(text)
        val destinationDate = relativeDestination(text, today)
        val sourceTime: LocalTime?
        val destinationTime: LocalTime?
        when {
            clocks.size >= 2 && moving -> {
                sourceTime = clocks.first()
                destinationTime = clocks.last()
            }
            clocks.size == 1 && moving && destinationDate != null -> {
                sourceTime = null
                destinationTime = clocks.single()
            }
            clocks.size == 1 && moving -> {
                sourceTime = clocks.single()
                destinationTime = null
            }
            clocks.size >= 2 -> {
                sourceTime = clocks.first()
                destinationTime = clocks.last()
            }
            else -> {
                sourceTime = clocks.singleOrNull()
                destinationTime = null
            }
        }
        return Parsed(
            patientName = extractName(text),
            sourceDate = null,
            sourceTime = sourceTime,
            destinationDate = destinationDate,
            destinationTime = destinationTime,
        )
    }

    private fun extractName(text: String): String? {
        val tokens = text.split(Regex("""\s+""")).mapNotNull { raw ->
            val token = raw.lowercase()
                .replace(Regex("""[''ʻ’`]"""), "'")
                .replace(Regex("""[^a-zа-яёўқғҳ'\-]+""", RegexOption.IGNORE_CASE), "")
                .trim('-', '\'')
            token.takeIf { it.length >= 3 && it !in STOP && !CLOCK.containsMatchIn(it) }
        }
        if (tokens.isEmpty() || tokens.size > 4) return null
        return tokens.joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    }

    private fun relativeDestination(text: String, today: LocalDate): LocalDate? {
        val t = text.lowercase()
        return when {
            Regex("""\b(tomorrow|завтра|ertaga|erta)\b""").containsMatchIn(t) -> today.plusDays(1)
            Regex("""\b(today|сегодня|bugun)\b""").containsMatchIn(t) -> today
            else -> null
        }
    }

    private fun toTime(match: MatchResult): LocalTime? {
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private val CLOCK = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val MOVE = Regex(
        """(?:reschedul\w*|move (the |my |this |that )?(appointment|visit)|""" +
            """ko['ʼʻ''`]?chir|kochir|o['ʼʻ''`]?tkaz|otkaz|перенес)""",
        RegexOption.IGNORE_CASE,
    )
    private val STOP = setOf(
        "appointment", "appointments", "reschedule", "rescheduling", "schedule", "change",
        "would", "like", "friday", "monday", "tuesday", "wednesday", "thursday", "saturday",
        "sunday", "with", "that", "this", "from", "have", "just", "said", "patient", "visit",
        "move", "last", "next", "week", "today", "tomorrow", "understood", "please",
        "tushunarli", "demak", "dagi", "bilan", "qilingan", "uchrashuvni", "uchrashuv",
        "ertaga", "erta", "ko'chiring", "kochiring", "ko'chir", "kochir", "qabul",
        "сегодня", "завтра", "перенеси", "перенесите", "приём", "прием",
    )
}
