package com.shifa.admin.application

import java.time.Instant

private const val SECONDS_PER_HOUR = 3600L

/** The views of the admin health page's charts: buckets aligned to UTC, oldest first, the last one still running. */
enum class MetricsRange(val param: String, val buckets: Int, bucketHours: Long) {
    DAY("24h", 24, 1),
    WEEK("7d", 28, 6),
    MONTH("30d", 30, 24),
    ;

    val bucketSeconds = bucketHours * SECONDS_PER_HOUR

    fun runningStart(now: Instant): Long = now.epochSecond / bucketSeconds * bucketSeconds
    fun windowStart(now: Instant): Long = runningStart(now) - (buckets - 1) * bucketSeconds

    /** How far back one full range reaches: a bucket minus this is its counterpart in the previous range. */
    val spanSeconds get() = buckets * bucketSeconds
    fun previousStart(now: Instant): Long = windowStart(now) - spanSeconds

    companion object {
        fun of(param: String): MetricsRange =
            entries.firstOrNull { it.param == param } ?: throw IllegalArgumentException("range must be 24h, 7d or 30d")
    }
}
