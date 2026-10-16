package com.shifa.intelligence.appointment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ClinicTimeRangeResolverTest {
    private val utc = ZoneOffset.UTC
    private val friday = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), utc)
    private val ranges = ClinicTimeRangeResolver(friday)

    @Test
    fun `calendar periods around a friday`() {
        assertWindow("today", "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", TimeRangeKind.CALENDAR)
        assertWindow("yesterday", "2026-10-15T00:00:00Z", "2026-10-16T00:00:00Z", TimeRangeKind.CALENDAR)
        assertWindow("tomorrow", "2026-10-17T00:00:00Z", "2026-10-18T00:00:00Z", TimeRangeKind.CALENDAR)
        assertWindow("this week", "2026-10-12T00:00:00Z", "2026-10-19T00:00:00Z", TimeRangeKind.CALENDAR)
        assertWindow("last week", "2026-10-05T00:00:00Z", "2026-10-12T00:00:00Z", TimeRangeKind.CALENDAR)
        assertWindow("next week", "2026-10-19T00:00:00Z", "2026-10-26T00:00:00Z", TimeRangeKind.CALENDAR)
        assertWindow("this month", "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z", TimeRangeKind.CALENDAR)
        assertWindow("last month", "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z", TimeRangeKind.CALENDAR)
        assertWindow("next month", "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z", TimeRangeKind.CALENDAR)
        assertWindow("this quarter", "2026-10-01T00:00:00Z", "2027-01-01T00:00:00Z", TimeRangeKind.CALENDAR)
        assertWindow("last quarter", "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z", TimeRangeKind.CALENDAR)
        assertWindow("this year", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", TimeRangeKind.CALENDAR)
        assertWindow("last year", "2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z", TimeRangeKind.CALENDAR)
    }

    @Test
    fun `yesterday phrasings share one window`() {
        val phrases = listOf(
            "yesterday",
            "How many appointments did I have yesterday?",
            "How many appointments were there yesterday?",
            "Count my appointments for yesterday.",
            "Give me yesterday's appointment total.",
        )
        val windows = phrases.map { ranges.findRanges(it, utc).single() }
        assertTrue(windows.all { it == windows.first() })
    }

    @Test
    fun `last week phrasings share one window and differ from last seven days`() {
        val phrases = listOf(
            "last week",
            "How many appointments did I have last week?",
            "Count my appointments for last week.",
        )
        val week = phrases.map { ranges.findRanges(it, utc).single() }
        assertTrue(week.all { it == week.first() })
        val rolling = ranges.findRanges("the last seven days", utc).single()
        assertEquals(TimeRangeKind.ROLLING, rolling.kind)
        assertEquals(Instant.parse("2026-10-09T10:00:00Z"), rolling.startInclusive)
        assertEquals(Instant.parse("2026-10-16T10:00:00Z"), rolling.endExclusive)
        assertNotEquals(week.first().startInclusive, rolling.startInclusive)
        assertNotEquals(week.first().endExclusive, rolling.endExclusive)
    }

    @Test
    fun `rolling periods are half open from now`() {
        val last30 = ranges.findRanges("last 30 days", utc).single()
        assertEquals(Instant.parse("2026-09-16T10:00:00Z"), last30.startInclusive)
        assertEquals(Instant.parse("2026-10-16T10:00:00Z"), last30.endExclusive)
        val last90 = ranges.findRanges("past ninety days", utc).single()
        assertEquals(Instant.parse("2026-07-18T10:00:00Z"), last90.startInclusive)
        val next14 = ranges.findRanges("next 14 days", utc).single()
        assertEquals(Instant.parse("2026-10-16T10:00:00Z"), next14.startInclusive)
        assertEquals(Instant.parse("2026-10-30T10:00:00Z"), next14.endExclusive)
        val twoWeeks = ranges.findRanges("past two weeks", utc).single()
        assertEquals(Instant.parse("2026-10-02T10:00:00Z"), twoWeeks.startInclusive)
        assertEquals(TimeRangeKind.ROLLING, twoWeeks.kind)
    }

    @Test
    fun `explicit ranges are inclusive start and exclusive end`() {
        val between = ranges.findRanges("between 2026-01-01 and 2026-01-31", utc).single()
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), between.startInclusive)
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), between.endExclusive)
        val on = ranges.findRanges("on 2026-06-01", utc).single()
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), on.startInclusive)
        assertEquals(Instant.parse("2026-06-02T00:00:00Z"), on.endExclusive)
        val since = ranges.findRanges("since 2026-01-01", utc).single()
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), since.startInclusive)
        assertTrue(since.capped)
        val before = ranges.findRanges("before 2026-06-01", utc).single()
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), before.endExclusive)
        assertEquals(Instant.parse("2016-06-01T00:00:00Z"), before.startInclusive)
        val after = ranges.findRanges("after 2026-06-01", utc).single()
        assertEquals(Instant.parse("2026-06-02T00:00:00Z"), after.startInclusive)
    }

    @Test
    fun `month year and leap boundaries`() {
        val newYear = ClinicTimeRangeResolver(Clock.fixed(Instant.parse("2026-01-01T00:30:00Z"), utc))
        assertEquals(
            Instant.parse("2025-12-31T00:00:00Z"),
            newYear.findRanges("yesterday", utc).single().startInclusive,
        )
        assertEquals(
            Instant.parse("2025-01-01T00:00:00Z"),
            newYear.findRanges("last year", utc).single().startInclusive,
        )
        val march = ClinicTimeRangeResolver(Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), utc))
        val lastMonth = march.findRanges("last month", utc).single()
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), lastMonth.startInclusive)
        assertEquals(Instant.parse("2026-03-01T00:00:00Z"), lastMonth.endExclusive)
        val leap = ClinicTimeRangeResolver(Clock.fixed(Instant.parse("2024-03-01T12:00:00Z"), utc))
        val yesterday = leap.findRanges("yesterday", utc).single()
        assertEquals(Instant.parse("2024-02-29T00:00:00Z"), yesterday.startInclusive)
        assertEquals(Instant.parse("2024-03-01T00:00:00Z"), yesterday.endExclusive)
    }

    @Test
    fun `monday is the start of this week and next week skips today`() {
        val monday = ClinicTimeRangeResolver(Clock.fixed(Instant.parse("2026-10-12T08:00:00Z"), utc))
        val thisWeek = monday.findRanges("this week", utc).single()
        val nextWeek = monday.findRanges("next week", utc).single()
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), thisWeek.startInclusive)
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), thisWeek.endExclusive)
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), nextWeek.startInclusive)
        assertEquals(thisWeek.endExclusive, nextWeek.startInclusive)
    }

    @Test
    fun `daylight saving local days use zone offsets`() {
        val newYork = ZoneId.of("America/New_York")
        val spring = ClinicTimeRangeResolver(Clock.fixed(Instant.parse("2026-03-08T12:00:00Z"), utc))
        val springToday = spring.findRanges("today", newYork).single()
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), springToday.startInclusive)
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), springToday.endExclusive)

        val berlin = ZoneId.of("Europe/Berlin")
        val fall = ClinicTimeRangeResolver(Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), utc))
        val fallToday = fall.findRanges("today", berlin).single()
        assertEquals(Instant.parse("2026-10-24T22:00:00Z"), fallToday.startInclusive)
        assertEquals(Instant.parse("2026-10-25T23:00:00Z"), fallToday.endExclusive)
    }

    @Test
    fun `same instant is a different local day in two clinic zones`() {
        val clock = Clock.fixed(Instant.parse("2026-10-16T20:00:00Z"), utc)
        val resolver = ClinicTimeRangeResolver(clock)
        val tashkent = resolver.findRanges("today", ZoneId.of("Asia/Tashkent")).single()
        val newYork = resolver.findRanges("today", ZoneId.of("America/New_York")).single()
        assertEquals(Instant.parse("2026-10-16T19:00:00Z"), tashkent.startInclusive)
        assertEquals(Instant.parse("2026-10-16T04:00:00Z"), newYork.startInclusive)
        assertNotEquals(tashkent.startInclusive, newYork.startInclusive)
    }

    @Test
    fun `end of one period is the start of the next`() {
        val yesterday = ranges.findRanges("yesterday", utc).single()
        val today = ranges.findRanges("today", utc).single()
        assertEquals(yesterday.endExclusive, today.startInclusive)
        assertFalse(yesterday.endExclusive.isAfter(today.startInclusive))
    }

    private fun assertWindow(phrase: String, start: String, end: String, kind: TimeRangeKind) {
        val window = ranges.findRanges(phrase, utc).single()
        assertEquals(Instant.parse(start), window.startInclusive, phrase)
        assertEquals(Instant.parse(end), window.endExclusive, phrase)
        assertEquals(kind, window.kind, phrase)
    }
}
