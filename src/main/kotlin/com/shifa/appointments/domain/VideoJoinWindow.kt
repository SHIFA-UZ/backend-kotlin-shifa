package com.shifa.appointments.domain

import java.time.Duration
import java.time.Instant

/** The span in which a video visit's room can be joined (UTC): [BEFORE_START] before the start to [AFTER_END] after the end. */
data class VideoJoinWindow(val from: Instant, val to: Instant) {
    fun isTooEarly(now: Instant): Boolean = now.isBefore(from)

    fun isTooLate(now: Instant): Boolean = now.isAfter(to)

    companion object {
        val BEFORE_START: Duration = Duration.ofMinutes(5)
        val AFTER_END: Duration = Duration.ofMinutes(15)

        fun of(startAt: Instant, endAt: Instant): VideoJoinWindow =
            VideoJoinWindow(startAt.minus(BEFORE_START), endAt.plus(AFTER_END))
    }
}
