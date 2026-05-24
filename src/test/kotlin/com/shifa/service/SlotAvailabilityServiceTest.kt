package com.shifa.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SlotAvailabilityServiceTest {

    private fun dto(start: Instant, end: Instant, locationId: Long? = null) =
        PatientDaySlotsService.AvailableSlotDto(
            startAt = start.toString(),
            endAt = end.toString(),
            slotMinutes = 60,
            locationId = locationId,
            locationLabel = null
        )

    @Test
    fun `covers consecutive two-slot range same location`() {
        val t0 = Instant.parse("2026-06-01T07:00:00Z")
        val t1 = Instant.parse("2026-06-01T08:00:00Z")
        val t2 = Instant.parse("2026-06-01T09:00:00Z")
        val slots = listOf(dto(t0, t1, 1L), dto(t1, t2, 1L))
        assertTrue(
            SlotAvailabilityService.coversRangeWithConsecutiveSlots(slots, t0, t2)
        )
    }

    @Test
    fun `rejects gap between slots`() {
        val t0 = Instant.parse("2026-06-01T07:00:00Z")
        val t1 = Instant.parse("2026-06-01T08:00:00Z")
        val tSkip = Instant.parse("2026-06-01T09:00:00Z")
        val t3 = Instant.parse("2026-06-01T10:00:00Z")
        val slots = listOf(dto(t0, t1, null), dto(tSkip, t3, null))
        assertFalse(
            SlotAvailabilityService.coversRangeWithConsecutiveSlots(slots, t0, t3)
        )
    }

    @Test
    fun `rejects bridging two locations`() {
        val t0 = Instant.parse("2026-06-01T07:00:00Z")
        val t1 = Instant.parse("2026-06-01T08:00:00Z")
        val t2 = Instant.parse("2026-06-01T09:00:00Z")
        val slots = listOf(dto(t0, t1, 10L), dto(t1, t2, 20L))
        assertFalse(
            SlotAvailabilityService.coversRangeWithConsecutiveSlots(slots, t0, t2)
        )
    }

    @Test
    fun `requires exact end boundary`() {
        val t0 = Instant.parse("2026-06-01T07:00:00Z")
        val t1 = Instant.parse("2026-06-01T08:00:00Z")
        val slots = listOf(dto(t0, t1, null))
        assertFalse(
            SlotAvailabilityService.coversRangeWithConsecutiveSlots(slots, t0, Instant.parse("2026-06-01T07:30:00Z"))
        )
    }

    @Test
    fun `allows null location anchoring chain`() {
        val t0 = Instant.parse("2026-06-01T07:00:00Z")
        val t1 = Instant.parse("2026-06-01T08:00:00Z")
        val t2 = Instant.parse("2026-06-01T09:00:00Z")
        val slots = listOf(dto(t0, t1, null), dto(t1, t2, null))
        assertTrue(
            SlotAvailabilityService.coversRangeWithConsecutiveSlots(slots, t0, t2)
        )
    }
}
