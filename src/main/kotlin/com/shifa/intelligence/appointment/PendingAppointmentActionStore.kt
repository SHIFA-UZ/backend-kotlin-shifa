package com.shifa.intelligence.appointment

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PendingCancellation(
    val token: String,
    val doctorId: Long,
    val appointmentIds: List<Long>,
    val fingerprints: Map<Long, String>,
    val createdAt: Instant,
)

@Component
class PendingAppointmentActionStore(
    private val clock: Clock,
) {
    private val pending = ConcurrentHashMap<Long, PendingCancellation>()

    fun save(value: PendingCancellation) {
        pending[value.doctorId] = value
    }

    fun get(doctorId: Long): PendingCancellation? {
        val current = pending[doctorId] ?: return null
        if (Duration.between(current.createdAt, clock.instant()) > TTL) {
            pending.remove(doctorId)
            return null
        }
        return current
    }

    fun clear(doctorId: Long) {
        pending.remove(doctorId)
    }

    fun newToken(): String = UUID.randomUUID().toString()

    companion object {
        val TTL: Duration = Duration.ofMinutes(10)
    }
}
