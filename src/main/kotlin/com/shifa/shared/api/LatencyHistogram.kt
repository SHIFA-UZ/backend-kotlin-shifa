package com.shifa.shared.api

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow

/** Four bins per doubling of the milliseconds: about 9% of error, and histograms add up across hours. */
class LatencyHistogram(bins: LongArray = LongArray(BINS), maxMs: Long = 0) {
    private val bins = bins.copyOf(BINS)
    var maxMs = maxMs
        private set
    var total = bins.sum()
        private set

    fun add(ms: Long) {
        val value = ms.coerceAtLeast(0)
        bins[floor(BINS_PER_DOUBLING * log2(value + 1.0)).toInt().coerceAtMost(BINS - 1)]++
        total++
        maxMs = maxOf(maxMs, value)
    }

    fun merge(other: LatencyHistogram) {
        other.bins.forEachIndexed { i, count -> bins[i] += count }
        total += other.total
        maxMs = maxOf(maxMs, other.maxMs)
    }

    fun percentile(fraction: Double): Long {
        val rank = ceil(fraction * total).toLong().coerceAtLeast(1)
        var seen = 0L
        bins.forEachIndexed { i, count ->
            seen += count
            if (seen >= rank) return minOf(ceil(2.0.pow((i + 1.0) / BINS_PER_DOUBLING)).toLong() - 1, maxMs)
        }
        return maxMs
    }

    fun toBins(): LongArray = bins.copyOf()

    private companion object {
        const val BINS = 96
        const val BINS_PER_DOUBLING = 4
    }
}
