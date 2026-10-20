package com.shifa.intelligence.appointment

import java.time.LocalDate
import java.time.LocalTime

/**
 * Decides when a spoken turn is a move, and which listed visit it refers to.
 * The model was pinning a stale focus patient and then saying the visit did not exist.
 */
object RescheduleIntent {
    data class Visit(
        val id: Long,
        val patientName: String,
        val day: LocalDate,
        val time: LocalTime,
    )

    data class Choice(
        val visitId: Long,
        val destination: LocalDate?,
        val time: LocalTime?,
        /** A day the doctor named that is already in the past. Not rolled into next year. */
        val pastDay: LocalDate? = null,
    )

    fun shouldMove(current: String, priorUsers: List<String>, priorAssistants: List<String>): Boolean {
        if (Regex("""(?:\bcancel\b|bekor|отмен)""", RegexOption.IGNORE_CASE).containsMatchIn(current)) return false
        if (isExplicitYes(current) && priorAssistants.any { it.contains("<appointment_action>") }) return false
        if (MOVE.containsMatchIn(current)) return true
        val thread = priorUsers.any { MOVE.containsMatchIn(it) }
        if (!thread) return false
        if (LIST_ONLY.containsMatchIn(current) && !MOVE.containsMatchIn(current)) return false
        return true
    }

    fun choose(
        visits: List<Visit>,
        current: String,
        priorUsers: List<String>,
        priorAssistants: List<String>,
        today: LocalDate,
        lastListedId: Long? = null,
    ): Choice? {
        if (visits.isEmpty()) return null
        val threadUsers = moveThread(priorUsers, current)
        val userBlob = threadUsers.joinToString("\n").lowercase()
        val confirmBlob = priorAssistants
            .filter { confirmationOfOneVisit(it) }
            .takeLast(2)
            .joinToString("\n")
            .lowercase()
        val blob = "$userBlob\n$confirmBlob"
        val shown = shownIds(priorAssistants)
        val lastId = when {
            shown.isNotEmpty() -> shown.last()
            else -> lastListedId
        }
        val saysLast = Regex("""\blast (appointment|visit)\b""", RegexOption.IGNORE_CASE).containsMatchIn(userBlob)
        val saysIt = Regex("""\b(it|that one|this one|that visit|this visit)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(current)
        val named = bestVisit(visits, blob)
        val visitId = when {
            named != null -> named.id
            saysLast && lastId != null && visits.any { it.id == lastId } -> lastId
            saysIt && shown.size == 1 -> shown.first()
            else -> null
        } ?: return null
        val visit = visits.first { it.id == visitId }
        val destination = destinationDay(threadUsers, priorAssistants, visit.day, today)
        val time = newTime(current, threadUsers, priorAssistants, destination, visit.time)
        val pastDay = if (destination == null) {
            pastDatesIn(current, today).lastOrNull()
        } else {
            null
        }
        return Choice(visitId, destination, time, pastDay)
    }

    fun textNamesSomeoneElse(text: String, patientName: String): Boolean {
        val blob = text.lowercase()
        val tokens = nameTokens(patientName)
        if (tokens.any { blob.contains(it) }) return false
        val foreign = WORD.findAll(blob).map { it.value }.filter { token ->
            token.length >= 4 && token !in STOP && token !in tokens
        }
        return foreign.any { word ->
            word !in MONTHS.keys && !MONTHS.keys.any { it.startsWith(word) }
        }
    }

    private fun bestVisit(visits: List<Visit>, blob: String): Visit? {
        val scored = visits.map { it to score(it, visits, blob) }
        val best = scored.maxByOrNull { it.second } ?: return null
        if (best.second < 4) return null
        val tied = scored.filter { it.second == best.second }
        if (tied.size > 1) return null
        return best.first
    }

    private fun score(visit: Visit, visits: List<Visit>, blob: String): Int {
        var score = 0
        val tokens = nameTokens(visit.patientName)
        val hits = tokens.count { blob.contains(it) }
        if (tokens.isNotEmpty() && hits == tokens.size) score += 6
        else if (hits > 0) {
            val first = tokens.first()
            val unique = visits.count { nameTokens(it.patientName).firstOrNull() == first } == 1
            if (unique && blob.contains(first)) score += 5
        }
        if (mentionsDay(blob, visit.day)) score += 4
        if (mentionsTime(blob, visit.time)) {
            score += 3
            if (visits.count { mentionsTime(blob, it.time) } == 1) score += 2
        }
        return score
    }

    private fun destinationDay(
        threadUsers: List<String>,
        priorAssistants: List<String>,
        sourceDay: LocalDate,
        today: LocalDate,
    ): LocalDate? {
        val fromUsers = threadUsers.flatMap { datesIn(it, today) }.lastOrNull { it != sourceDay }
        if (fromUsers != null) return fromUsers
        val relative = threadUsers.lastOrNull { WEEKS.containsMatchIn(it) }?.let { weeksPhrase(it) }
        if (relative != null) return sourceDay.plusWeeks(relative.toLong())
        val yes = threadUsers.lastOrNull()?.trim()?.equals("yes", ignoreCase = true) == true
        if (!yes) return null
        val asked = priorAssistants.lastOrNull { confirmationOfOneVisit(it) } ?: return null
        return datesIn(asked, today).lastOrNull { it != sourceDay }
    }

    private fun newTime(
        current: String,
        threadUsers: List<String>,
        priorAssistants: List<String>,
        destination: LocalDate?,
        sourceTime: LocalTime,
    ): LocalTime? {
        if (destination == null) return null
        val named = threadUsers.asReversed().firstNotNullOfOrNull { line ->
            clockIn(line)?.takeIf { it != sourceTime }
        }
        if (named != null) return named
        if (!isExplicitYes(current)) return null
        val asked = priorAssistants.lastOrNull { confirmationOfOneVisit(it) } ?: return null
        return clockIn(asked)?.takeIf { it != sourceTime }
    }

    private fun moveThread(priorUsers: List<String>, current: String): List<String> {
        val all = priorUsers + current
        val start = all.indexOfLast { MOVE.containsMatchIn(it) }
        if (start < 0) return listOf(current)
        return all.drop(start)
    }

    private fun confirmationOfOneVisit(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("appointment with") && !t.contains("appointments")
    }

    fun queryWindow(messages: List<String>): Pair<String, String>? {
        val json = messages.asReversed().firstNotNullOfOrNull { QUERY.find(it)?.groupValues?.get(1) } ?: return null
        val start = Regex(""""start"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
        val end = Regex(""""endExclusive"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
        return start to end
    }

    fun shownIds(messages: List<String>): List<Long> {
        val raw = messages.asReversed().firstNotNullOfOrNull { LIST.find(it)?.groupValues?.get(1) } ?: return emptyList()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }
    }

    fun datesIn(text: String, today: LocalDate): List<LocalDate> =
        rawDates(text, today).filter { !it.isBefore(today) }

    fun pastDatesIn(text: String, today: LocalDate): List<LocalDate> =
        rawDates(text, today).filter { it.isBefore(today) }

    private fun rawDates(text: String, today: LocalDate): List<LocalDate> =
        ClinicCalendarDay.findDayMonths(text, today).map { it.date }

    private fun weeksPhrase(text: String): Int? {
        val match = WEEKS.find(text.lowercase()) ?: return null
        return when (match.groupValues[1]) {
            "one", "a" -> 1
            "two" -> 2
            "three" -> 3
            "four" -> 4
            else -> match.groupValues[1].toIntOrNull()
        }
    }

    fun clockIn(text: String): LocalTime? {
        val spaced = text.lowercase().replace(".", "")
        val match = CLOCK.findAll(spaced).lastOrNull() ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        if (minute !in 0..59) return null
        val marker = match.groupValues[3].lowercase()
        if (marker.startsWith("p")) {
            if (hour !in 1..12) return null
            if (hour != 12) hour += 12
        } else if (marker.startsWith("a")) {
            if (hour !in 1..12) return null
            if (hour == 12) hour = 0
        } else if (hour !in 0..23) {
            return null
        }
        return LocalTime.of(hour, minute)
    }

    private fun mentionsDay(blob: String, day: LocalDate): Boolean =
        ClinicCalendarDay.findDayMonths(blob, day).any {
            it.date.month == day.month && it.date.dayOfMonth == day.dayOfMonth
        }

    private fun mentionsTime(blob: String, time: LocalTime): Boolean {
        val spaced = blob.replace(".", "")
        if (spaced.contains("%02d:%02d".format(time.hour, time.minute))) return true
        if (spaced.contains("%d:%02d".format(time.hour, time.minute))) return true
        val h12 = if (time.hour % 12 == 0) 12 else time.hour % 12
        val suffix = if (time.hour < 12) "a" else "p"
        if (Regex("""\b$h12:${"%02d".format(time.minute)}\s*$suffix""").containsMatchIn(spaced)) return true
        return false
    }

    private fun nameTokens(fullName: String): List<String> =
        fullName.lowercase().split(Regex("""\s+""")).filter { it.length >= 4 && it.any { ch -> ch.isLetter() } }

    private fun isExplicitYes(text: String): Boolean =
        text.trim().equals("yes", ignoreCase = true) || text.trim().equals("yeah", ignoreCase = true)

    private val MOVE = Regex(
        """(?:reschedul\w*|move (the |my |this |that )?(appointment|visit)|change the (last |next )?(appointment|visit)|""" +
            """ko['ʼʻ''`]?chir|kochir|o['ʼʻ''`]?tkaz|otkaz|перенес)""",
        RegexOption.IGNORE_CASE,
    )
    private val LIST_ONLY = Regex("""\b(list|show me|how many)\b""", RegexOption.IGNORE_CASE)
    private val WEEKS = Regex("""\bin\s+(a|one|two|three|four|\d+)\s+weeks?\b""", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("""\b(\d{1,2}):(\d{2})\s*([ap]m?)?\b""", RegexOption.IGNORE_CASE)
    private val LIST = Regex("""<appointment_list>([\d,\s]+)</appointment_list>""")
    private val QUERY = Regex("""<appointment_query>(.*?)</appointment_query>""", RegexOption.DOT_MATCHES_ALL)
    private val WORD = Regex("""[a-z]{4,}""")
    private val MONTHS = mapOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6,
        "july" to 7, "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12,
    )
    private val STOP = setOf(
        "appointment", "appointments", "reschedule", "rescheduling", "schedule", "change", "would", "like",
        "friday", "monday", "tuesday", "wednesday", "thursday", "saturday", "sunday", "with", "that", "this",
        "from", "have", "just", "said", "beginning", "patient", "visit", "move", "last", "next", "week",
    )
}
