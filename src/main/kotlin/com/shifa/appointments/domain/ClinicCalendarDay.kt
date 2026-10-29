package com.shifa.appointments.domain

import java.time.LocalDate

/**
 * Day+month with no year, and ISO dates with a stale year, map onto the practice clock year.
 * Models still invent 2023/2024 when the doctor says "23 October" / "23-oktabr".
 */
object ClinicCalendarDay {
    fun alignYear(date: LocalDate, today: LocalDate): LocalDate {
        if (date.year >= today.year) return date
        return try {
            date.withYear(today.year)
        } catch (_: Exception) {
            LocalDate.of(today.year, date.month, date.month.length(today.isLeapYear))
        }
    }

    fun parse(raw: String, today: LocalDate): LocalDate? {
        val t = normalize(raw)
        ISO.find(t)?.let { return alignYear(LocalDate.parse(it.value), today) }
        return findDayMonths(t, today).firstOrNull()?.date
    }

    fun findDayMonths(text: String, today: LocalDate): List<Hit> {
        val hits = mutableListOf<Hit>()
        fun add(start: Int, end: Int, date: LocalDate) {
            if (start < 0 || end <= start) return
            if (hits.any { it.start < end && start < it.end }) return
            hits += Hit(start, end, date)
        }
        DAY_MONTH.findAll(text).forEach { m ->
            val day = m.groupValues[1].toIntOrNull() ?: return@forEach
            val month = monthNumber(m.groupValues[2]) ?: return@forEach
            val year = m.groupValues[3].toIntOrNull()
            dateOf(today, year, month, day)?.let { add(m.range.first, m.range.last + 1, it) }
        }
        MONTH_DAY.findAll(text).forEach { m ->
            val month = monthNumber(m.groupValues[1]) ?: return@forEach
            val day = m.groupValues[2].toIntOrNull() ?: return@forEach
            val year = m.groupValues[3].toIntOrNull()
            dateOf(today, year, month, day)?.let { add(m.range.first, m.range.last + 1, it) }
        }
        return hits.sortedBy { it.start }
    }

    data class Hit(val start: Int, val end: Int, val date: LocalDate)

    private fun dateOf(today: LocalDate, year: Int?, month: Int, day: Int): LocalDate? {
        val raw = try {
            LocalDate.of(year ?: today.year, month, day)
        } catch (_: Exception) {
            return null
        }
        return alignYear(raw, today)
    }

    private fun monthNumber(raw: String): Int? {
        val token = raw.lowercase()
        MONTHS[token]?.let { return it }
        return MONTHS.entries.firstOrNull { (name, _) ->
            name.length >= 5 && token.startsWith(name) && token.removePrefix(name) in SUFFIXES
        }?.value
    }

    private val ISO = Regex("""\b(\d{4}-\d{2}-\d{2})\b""")
    private val SUFFIXES = setOf("", "dagi", "da", "dan", "ning", "ni", "ga", "gi")
    private val MONTHS = mapOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6,
        "july" to 7, "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12,
        "yanvar" to 1, "fevral" to 2, "mart" to 3, "aprel" to 4, "iyun" to 6, "iyul" to 7,
        "avgust" to 8, "sentabr" to 9, "sentyabr" to 9, "oktabr" to 10, "oktyabr" to 10, "noyabr" to 11, "dekabr" to 12,
        "января" to 1, "февраля" to 2, "марта" to 3, "апреля" to 4, "мая" to 5, "июня" to 6,
        "июля" to 7, "августа" to 8, "сентября" to 9, "октября" to 10, "ноября" to 11, "декабря" to 12,
        "январь" to 1, "февраль" to 2, "март" to 3, "апрель" to 4, "май" to 5, "июнь" to 6,
        "июль" to 7, "август" to 8, "сентябрь" to 9, "октябрь" to 10, "ноябрь" to 11, "декабрь" to 12,
    )
    private val MONTH_ALT = MONTHS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val DAY_MONTH = Regex(
        """\b(\d{1,2})(?:st|nd|rd|th)?(?:[\s\-]+of)?[\s\-]+($MONTH_ALT)[a-zа-яёʻ']*(?:[\s\-]+(\d{4}))?""",
        RegexOption.IGNORE_CASE,
    )
    private val MONTH_DAY = Regex(
        """\b($MONTH_ALT)[a-zа-яёʻ']*(?:ning)?[\s\-]+(\d{1,2})(?:st|nd|rd|th)?(?:[\s\-]+(\d{4}))?\b""",
        RegexOption.IGNORE_CASE,
    )
}
