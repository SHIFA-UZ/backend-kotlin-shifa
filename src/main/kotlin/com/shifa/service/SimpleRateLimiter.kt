package com.shifa.service

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class SimpleRateLimiter(
    private val limitPerMinute: Int
) {

    private var windowStart = Instant.now().epochSecond
    private val counter = AtomicInteger(0)

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = Instant.now().epochSecond
        if (now - windowStart >= 60) {
            windowStart = now
            counter.set(0)
        }
        return counter.incrementAndGet() <= limitPerMinute
    }
}
