package com.v2ray.ang.delta

import java.util.Locale

/** Xray returns byte deltas (read-and-reset), not cumulative counters. */
object DeltaTraffic {
    const val MESSAGE = 17501
    const val EXTRA = "deltaTrafficSample"
    const val INTERVAL_MS = 1000L
    const val MAX_AGE_MS = 2500L
    fun rate(bytes: Long, elapsed: Long): Long =
        if (bytes <= 0L || elapsed <= 0L) 0L
        else (bytes.toDouble() * 1000.0 / elapsed.toDouble()).toLong().coerceAtLeast(0L)

    fun add(a: Long, b: Long): Long {
        val x = a.coerceAtLeast(0L)
        val y = b.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - x < y) Long.MAX_VALUE else x + y
    }

    fun format(value: Long): String {
        val n = value.coerceAtLeast(0L)
        if (n < 1024L) return "$n B/s"
        val units = arrayOf("KB/s", "MB/s", "GB/s", "TB/s", "PB/s", "EB/s")
        var amount = n / 1024.0
        var index = 0
        while (amount >= 1024.0 && index < units.lastIndex) {
            amount /= 1024.0
            index++
        }
        return String.format(Locale.US, "%.1f %s", amount, units[index])
    }

    fun fresh(sample: LongArray?, now: Long, acceptAfter: Long): Boolean =
        sample != null && sample.size == 3 && sample[0] >= acceptAfter &&
            sample[0] <= now && now - sample[0] <= MAX_AGE_MS &&
            sample[1] >= 0L && sample[2] >= 0L
}

/** Discard first read, generation changes and long gaps (screen off/reload). */
class DeltaTrafficWindow {
    private var previous = -1L
    private var generation = -1L
    fun reset() { previous = -1L; generation = -1L }
    fun interval(now: Long, epoch: Long): Long? {
        val elapsed = now - previous
        val valid = previous >= 0L && epoch >= 0L && epoch == generation &&
            elapsed in 1L..5000L
        previous = now
        generation = epoch
        return elapsed.takeIf { valid }
    }
}
