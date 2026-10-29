package com.shifa.appointments.domain

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Resolves calendar periods and rolling durations for one clinic zone.
 *
 * Weeks start on Monday, matching the morning assistant. Intervals are half-open:
 * startInclusive <= timestamp < endExclusive. "Last week" is the previous
 * Monday-to-Monday window. "The last 7 days" ends at the current instant.
 *
 * [zoneOf] uses the doctor's IANA zone. A blank or invalid value falls back to
 * Asia/Tashkent, the existing profile default, not a universal clinic zone.
 */
@Component
class ClinicTimeRangeResolver(
    private val clock: Clock,
) {
    fun zoneOf(timeZone: String?): ZoneId =
        try {
            ZoneId.of(timeZone?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_ZONE)
        } catch (_: Exception) {
            ZoneId.of(DEFAULT_ZONE)
        }

    fun resolve(spec: TimeSpec, zone: ZoneId): ResolvedTimeRange = when (spec) {
        is TimeSpec.Fixed -> spec.range
        is TimeSpec.Calendar -> resolvePeriod(spec.period, zone)
        is TimeSpec.Rolling -> resolveRolling(spec.direction, spec.count, spec.unit, zone)
        is TimeSpec.ExplicitRange -> {
            val today = LocalDate.now(clock.withZone(zone))
            between(ClinicCalendarDay.alignYear(spec.start, today), ClinicCalendarDay.alignYear(spec.end, today), zone)
        }
        is TimeSpec.Expression -> {
            val found = findRanges(spec.text, zone)
            if (found.isEmpty()) {
                throw TimeRangeParseException("I could not tell which dates you mean.")
            }
            found.first()
        }
    }

    fun resolvePeriod(period: CalendarPeriod, zone: ZoneId): ResolvedTimeRange {
        val today = LocalDate.now(clock.withZone(zone))
        return when (period) {
            CalendarPeriod.TODAY -> day(today, zone, "today")
            CalendarPeriod.YESTERDAY -> day(today.minusDays(1), zone, "yesterday")
            CalendarPeriod.TOMORROW -> day(today.plusDays(1), zone, "tomorrow")
            CalendarPeriod.THIS_WEEK -> weekStarting(mondayOf(today), zone, "this week")
            CalendarPeriod.LAST_WEEK -> weekStarting(mondayOf(today).minusWeeks(1), zone, "last week")
            CalendarPeriod.NEXT_WEEK ->
                weekStarting(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), zone, "next week")
            CalendarPeriod.THIS_MONTH -> monthStarting(today.withDayOfMonth(1), zone, "this month")
            CalendarPeriod.LAST_MONTH ->
                monthStarting(today.withDayOfMonth(1).minusMonths(1), zone, "last month")
            CalendarPeriod.NEXT_MONTH ->
                monthStarting(today.withDayOfMonth(1).plusMonths(1), zone, "next month")
            CalendarPeriod.THIS_QUARTER -> quarterStarting(quarterStart(today), zone, "this quarter")
            CalendarPeriod.LAST_QUARTER ->
                quarterStarting(quarterStart(today).minusMonths(3), zone, "last quarter")
            CalendarPeriod.NEXT_QUARTER ->
                quarterStarting(quarterStart(today).plusMonths(3), zone, "next quarter")
            CalendarPeriod.THIS_YEAR -> yearStarting(LocalDate.of(today.year, 1, 1), zone, "this year")
            CalendarPeriod.LAST_YEAR -> yearStarting(LocalDate.of(today.year - 1, 1, 1), zone, "last year")
            CalendarPeriod.NEXT_YEAR -> yearStarting(LocalDate.of(today.year + 1, 1, 1), zone, "next year")
        }
    }

    fun resolveRolling(
        direction: RollingDirection,
        count: Int,
        unit: RollingUnit,
        zone: ZoneId,
    ): ResolvedTimeRange {
        val max = when (unit) {
            RollingUnit.DAYS -> 366
            RollingUnit.WEEKS -> 52
            RollingUnit.MONTHS -> 36
        }
        if (count !in 1..max) {
            throw TimeRangeParseException("That period is outside the supported range.")
        }
        val now = clock.instant().atZone(zone)
        val shifted = when (unit) {
            RollingUnit.DAYS -> now.plusDays(signed(direction, count))
            RollingUnit.WEEKS -> now.plusWeeks(signed(direction, count))
            RollingUnit.MONTHS -> now.plusMonths(signed(direction, count))
        }
        val start = if (direction == RollingDirection.NEXT) now.toInstant() else shifted.toInstant()
        val end = if (direction == RollingDirection.NEXT) shifted.toInstant() else now.toInstant()
        val unitWord = when (unit) {
            RollingUnit.DAYS -> if (count == 1) "day" else "days"
            RollingUnit.WEEKS -> if (count == 1) "week" else "weeks"
            RollingUnit.MONTHS -> if (count == 1) "month" else "months"
        }
        val label = if (direction == RollingDirection.NEXT) "the next $count $unitWord" else "the last $count $unitWord"
        return ResolvedTimeRange(start, end, label, TimeRangeKind.ROLLING, capped = false)
    }

    /**
     * Non-overlapping period phrases in [text], earliest first.
     * Two hits are left for the caller to treat as a comparison.
     */
    fun findRanges(text: String, zone: ZoneId): List<ResolvedTimeRange> {
        val t = normalize(text)
        if (t.isEmpty()) return emptyList()
        val hits = mutableListOf<Hit>()
        fun add(start: Int, end: Int, range: ResolvedTimeRange) {
            if (start < 0 || end <= start) return
            if (hits.any { it.start < end && start < it.end }) return
            hits += Hit(start, end, range)
        }

        val endOfNext = "end of next week"
        val endIdx = t.indexOf(endOfNext)
        if (endIdx >= 0) {
            val friday = fridayOfNextWeek(LocalDate.now(clock.withZone(zone)))
            add(endIdx, endIdx + endOfNext.length, day(friday, zone, "Friday of next week"))
        }
        val today = LocalDate.now(clock.withZone(zone))
        BETWEEN.findAll(t).forEach { m ->
            add(
                m.range.first,
                m.range.last + 1,
                between(
                    ClinicCalendarDay.alignYear(LocalDate.parse(m.groupValues[1]), today),
                    ClinicCalendarDay.alignYear(LocalDate.parse(m.groupValues[2]), today),
                    zone,
                ),
            )
        }
        SINCE.findAll(t).forEach { m ->
            add(m.range.first, m.range.last + 1, since(ClinicCalendarDay.alignYear(LocalDate.parse(m.groupValues[1]), today), zone))
        }
        BEFORE.findAll(t).forEach { m ->
            add(m.range.first, m.range.last + 1, before(ClinicCalendarDay.alignYear(LocalDate.parse(m.groupValues[1]), today), zone))
        }
        AFTER.findAll(t).forEach { m ->
            add(m.range.first, m.range.last + 1, after(ClinicCalendarDay.alignYear(LocalDate.parse(m.groupValues[1]), today), zone))
        }
        ON_DATE.findAll(t).forEach { m ->
            val date = ClinicCalendarDay.alignYear(LocalDate.parse(m.groupValues[1]), today)
            add(m.range.first, m.range.last + 1, day(date, zone, "on ${speak(date)}"))
        }
        ClinicCalendarDay.findDayMonths(t, today).forEach { hit ->
            add(hit.start, hit.end, day(hit.date, zone, "on ${speak(hit.date)}"))
        }
        ROLLING.findAll(t).forEach { m ->
            val direction = if (m.groupValues[1] == "next") RollingDirection.NEXT else RollingDirection.LAST
            val count = numberToken(m.groupValues[2])
            val unit = when {
                m.groupValues[3].startsWith("day") -> RollingUnit.DAYS
                m.groupValues[3].startsWith("week") -> RollingUnit.WEEKS
                else -> RollingUnit.MONTHS
            }
            add(m.range.first, m.range.last + 1, resolveRolling(direction, count, unit, zone))
        }
        ISO.findAll(t).forEach { m ->
            val date = ClinicCalendarDay.alignYear(LocalDate.parse(m.groupValues[1]), today)
            add(m.range.first, m.range.last + 1, day(date, zone, "on ${speak(date)}"))
        }
        for ((phrase, period) in CALENDAR_PHRASES) {
            var idx = t.indexOf(phrase)
            while (idx >= 0) {
                val end = idx + phrase.length
                if (bounded(t, idx, end)) add(idx, end, resolvePeriod(period, zone))
                idx = t.indexOf(phrase, end)
            }
        }
        return hits.sortedBy { it.start }.map { it.range }
    }

    private fun between(start: LocalDate, end: LocalDate, zone: ZoneId): ResolvedTimeRange {
        if (end.isBefore(start)) throw TimeRangeParseException("The end date is before the start date.")
        return ResolvedTimeRange(
            startInclusive = start.atStartOfDay(zone).toInstant(),
            endExclusive = end.plusDays(1).atStartOfDay(zone).toInstant(),
            label = "from ${speak(start)} until ${speak(end)}",
            kind = TimeRangeKind.EXPLICIT,
            capped = false,
        )
    }

    private fun since(date: LocalDate, zone: ZoneId): ResolvedTimeRange {
        val start = date.atStartOfDay(zone).toInstant()
        val end = clock.instant().atZone(zone).plusYears(FUTURE_CAP_YEARS).toInstant()
        if (!end.isAfter(start)) throw TimeRangeParseException("That start date is too far in the future.")
        return ResolvedTimeRange(start, end, "since ${speak(date)}", TimeRangeKind.EXPLICIT, capped = true)
    }

    private fun before(date: LocalDate, zone: ZoneId): ResolvedTimeRange {
        val end = date.atStartOfDay(zone).toInstant()
        val start = date.minusYears(PAST_CAP_YEARS).atStartOfDay(zone).toInstant()
        return ResolvedTimeRange(start, end, "before ${speak(date)}", TimeRangeKind.EXPLICIT, capped = true)
    }

    private fun after(date: LocalDate, zone: ZoneId): ResolvedTimeRange {
        val start = date.plusDays(1).atStartOfDay(zone).toInstant()
        val end = clock.instant().atZone(zone).plusYears(FUTURE_CAP_YEARS).toInstant()
        if (!end.isAfter(start)) throw TimeRangeParseException("That date is too far in the future.")
        return ResolvedTimeRange(start, end, "after ${speak(date)}", TimeRangeKind.EXPLICIT, capped = true)
    }

    private fun day(date: LocalDate, zone: ZoneId, label: String) = ResolvedTimeRange(
        startInclusive = date.atStartOfDay(zone).toInstant(),
        endExclusive = date.plusDays(1).atStartOfDay(zone).toInstant(),
        label = label,
        kind = TimeRangeKind.CALENDAR,
        capped = false,
    )

    private fun weekStarting(monday: LocalDate, zone: ZoneId, label: String) = ResolvedTimeRange(
        startInclusive = monday.atStartOfDay(zone).toInstant(),
        endExclusive = monday.plusWeeks(1).atStartOfDay(zone).toInstant(),
        label = label,
        kind = TimeRangeKind.CALENDAR,
        capped = false,
    )

    private fun monthStarting(first: LocalDate, zone: ZoneId, label: String) = ResolvedTimeRange(
        startInclusive = first.atStartOfDay(zone).toInstant(),
        endExclusive = first.plusMonths(1).atStartOfDay(zone).toInstant(),
        label = label,
        kind = TimeRangeKind.CALENDAR,
        capped = false,
    )

    private fun quarterStarting(first: LocalDate, zone: ZoneId, label: String) = ResolvedTimeRange(
        startInclusive = first.atStartOfDay(zone).toInstant(),
        endExclusive = first.plusMonths(3).atStartOfDay(zone).toInstant(),
        label = label,
        kind = TimeRangeKind.CALENDAR,
        capped = false,
    )

    private fun yearStarting(first: LocalDate, zone: ZoneId, label: String) = ResolvedTimeRange(
        startInclusive = first.atStartOfDay(zone).toInstant(),
        endExclusive = first.plusYears(1).atStartOfDay(zone).toInstant(),
        label = label,
        kind = TimeRangeKind.CALENDAR,
        capped = false,
    )

    private fun mondayOf(today: LocalDate): LocalDate =
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun quarterStart(today: LocalDate): LocalDate {
        val month = ((today.monthValue - 1) / 3) * 3 + 1
        return LocalDate.of(today.year, month, 1)
    }

    private fun fridayOfNextWeek(today: LocalDate): LocalDate =
        today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusDays(4)

    private fun signed(direction: RollingDirection, count: Int): Long =
        if (direction == RollingDirection.NEXT) count.toLong() else -count.toLong()

    private fun speak(date: LocalDate): String = date.format(SPOKEN_DATE)

    private fun numberToken(token: String): Int =
        token.toIntOrNull()
            ?: NUMBER_WORDS[token]
            ?: throw TimeRangeParseException("I could not read that number of days.")

    private fun bounded(text: String, start: Int, end: Int): Boolean {
        val beforeOk = start == 0 || !text[start - 1].isLetter()
        val afterOk = end >= text.length || !text[end].isLetter()
        return beforeOk && afterOk
    }

    private data class Hit(val start: Int, val end: Int, val range: ResolvedTimeRange)

    companion object {
        const val DEFAULT_ZONE = "Asia/Tashkent"
        private const val PAST_CAP_YEARS = 10L
        private const val FUTURE_CAP_YEARS = 2L
        private val SPOKEN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
        private val BETWEEN = Regex(
            """\b(?:between|from)\s+(\d{4}-\d{2}-\d{2})\s+(?:and|until|to|through)\s+(\d{4}-\d{2}-\d{2})\b""",
        )
        private val SINCE = Regex("""\bsince\s+(\d{4}-\d{2}-\d{2})\b""")
        private val BEFORE = Regex("""\bbefore\s+(\d{4}-\d{2}-\d{2})\b""")
        private val AFTER = Regex("""\bafter\s+(\d{4}-\d{2}-\d{2})\b""")
        private val ON_DATE = Regex("""\bon\s+(\d{4}-\d{2}-\d{2})\b""")
        private val ISO = Regex("""\b(\d{4}-\d{2}-\d{2})\b""")
        private val ROLLING = Regex(
            """\b(last|past|previous|next)\s+(\d+|one|two|three|four|five|six|seven|eight|nine|ten|twelve|fourteen|fifteen|thirty|sixty|ninety)\s+(day|days|week|weeks|month|months)\b""",
        )
        private val NUMBER_WORDS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
            "twelve" to 12, "fourteen" to 14, "fifteen" to 15,
            "thirty" to 30, "sixty" to 60, "ninety" to 90,
        )
        private val CALENDAR_PHRASES: List<Pair<String, CalendarPeriod>> = listOf(
            "на прошлой неделе" to CalendarPeriod.LAST_WEEK,
            "на следующей неделе" to CalendarPeriod.NEXT_WEEK,
            "на этой неделе" to CalendarPeriod.THIS_WEEK,
            "в прошлом месяце" to CalendarPeriod.LAST_MONTH,
            "в следующем месяце" to CalendarPeriod.NEXT_MONTH,
            "в этом месяце" to CalendarPeriod.THIS_MONTH,
            "прошлой неделе" to CalendarPeriod.LAST_WEEK,
            "следующей неделе" to CalendarPeriod.NEXT_WEEK,
            "этой неделе" to CalendarPeriod.THIS_WEEK,
            "o'tgan hafta" to CalendarPeriod.LAST_WEEK,
            "otgan hafta" to CalendarPeriod.LAST_WEEK,
            "keyingi hafta" to CalendarPeriod.NEXT_WEEK,
            "shu hafta" to CalendarPeriod.THIS_WEEK,
            "last quarter" to CalendarPeriod.LAST_QUARTER,
            "next quarter" to CalendarPeriod.NEXT_QUARTER,
            "this quarter" to CalendarPeriod.THIS_QUARTER,
            "last month" to CalendarPeriod.LAST_MONTH,
            "next month" to CalendarPeriod.NEXT_MONTH,
            "this month" to CalendarPeriod.THIS_MONTH,
            "last year" to CalendarPeriod.LAST_YEAR,
            "next year" to CalendarPeriod.NEXT_YEAR,
            "this year" to CalendarPeriod.THIS_YEAR,
            "last week" to CalendarPeriod.LAST_WEEK,
            "next week" to CalendarPeriod.NEXT_WEEK,
            "this week" to CalendarPeriod.THIS_WEEK,
            "yesterday" to CalendarPeriod.YESTERDAY,
            "tomorrow" to CalendarPeriod.TOMORROW,
            "сегодня" to CalendarPeriod.TODAY,
            "вчера" to CalendarPeriod.YESTERDAY,
            "завтра" to CalendarPeriod.TOMORROW,
            "ertaga" to CalendarPeriod.TOMORROW,
            "bugun" to CalendarPeriod.TODAY,
            "kecha" to CalendarPeriod.YESTERDAY,
            "today" to CalendarPeriod.TODAY,
        )
    }
}

enum class TimeRangeKind { CALENDAR, ROLLING, EXPLICIT }

enum class CalendarPeriod {
    TODAY, YESTERDAY, TOMORROW,
    THIS_WEEK, LAST_WEEK, NEXT_WEEK,
    THIS_MONTH, LAST_MONTH, NEXT_MONTH,
    THIS_QUARTER, LAST_QUARTER, NEXT_QUARTER,
    THIS_YEAR, LAST_YEAR, NEXT_YEAR,
}

enum class RollingDirection { LAST, NEXT }

enum class RollingUnit { DAYS, WEEKS, MONTHS }

data class ResolvedTimeRange(
    val startInclusive: Instant,
    val endExclusive: Instant,
    val label: String,
    val kind: TimeRangeKind,
    val capped: Boolean,
)

sealed class TimeSpec {
    data class Fixed(val range: ResolvedTimeRange) : TimeSpec()
    data class Calendar(val period: CalendarPeriod) : TimeSpec()
    data class Rolling(val direction: RollingDirection, val count: Int, val unit: RollingUnit) : TimeSpec()
    data class ExplicitRange(val start: LocalDate, val end: LocalDate) : TimeSpec()
    data class Expression(val text: String) : TimeSpec()
}

class TimeRangeParseException(message: String) : IllegalArgumentException(message)

internal fun normalize(raw: String): String =
    raw.trim().lowercase().replace(',', ' ').replace(Regex("""\s+"""), " ").trim()
