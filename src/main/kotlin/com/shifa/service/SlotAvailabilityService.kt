package com.shifa.service

import com.shifa.domain.DoctorProfile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Validates that an appointment sits on consecutive generated schedule slots with no gaps.
 * Lives server-side so stale clients cannot book arbitrary durations that only overlap-check;
 * [PatientDaySlotsService] remains the single source for slot boundaries.
 *
 * Mirrors the doctor-app algorithm in `consecutive_slot_range.dart` for UX parity.
 */
@Service
class SlotAvailabilityService(
    private val slots: PatientDaySlotsService
) {

    fun assertBookableConsecutiveRange(
        doctor: DoctorProfile,
        startAt: Instant,
        endAt: Instant,
        filterLocationId: Long?,
        excludeAppointmentId: Long? = null,
    ) {
        if (!endAt.isAfter(startAt)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_RANGE_MSG)
        }
        val durationMinutes = Duration.between(startAt, endAt).toMinutes()
        if (durationMinutes < MIN_RANGE_MINUTES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_RANGE_MSG)
        }

        val zone = ZoneId.of(doctor.timeZone)
        val startDay = startAt.atZone(zone).toLocalDate()
        val endDay = endAt.atZone(zone).toLocalDate()
        if (startDay != endDay) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Appointment must start and end on the same calendar day")
        }

        val available = slots.availableSlotsForDay(
            doctor = doctor,
            localDate = startDay,
            locationId = filterLocationId,
            excludeAppointmentId = excludeAppointmentId
        )

        if (!coversRangeWithConsecutiveSlots(available, startAt, endAt)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, RANGE_NOT_AVAILABLE_MSG)
        }
    }

    companion object {
        const val RANGE_NOT_AVAILABLE_MSG = "Selected time range is not fully available"
        const val INVALID_RANGE_MSG = "Invalid appointment time range"

        private const val MIN_RANGE_MINUTES = 5L

        /**
         * True when [startAt]..[endAt] partitions exactly into adjacent free-slot segments.
         * The first segment fixes [AvailableSlotDto.locationId]; chained segments must match (no bridging venues).
         */
        internal fun coversRangeWithConsecutiveSlots(
            dtos: List<PatientDaySlotsService.AvailableSlotDto>,
            startAt: Instant,
            endAt: Instant
        ): Boolean {
            val byStart = dtos.associateBy { Instant.parse(it.startAt) }
            val first = byStart[startAt] ?: return false
            val anchorLocationId = first.locationId
            var cursor = startAt
            while (cursor.isBefore(endAt)) {
                val slot = byStart[cursor] ?: return false
                if (slot.locationId != anchorLocationId) return false
                cursor = Instant.parse(slot.endAt)
            }
            return cursor == endAt
        }
    }
}
