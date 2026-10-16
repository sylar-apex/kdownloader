package org.app.kdownloader.engine

/** Smoothed download speed from successive byte counts. */
internal class SpeedMeter {
    private var lastBytes = -1L
    private var lastMs = 0L
    private var speed = 0.0

    fun sample(bytes: Long, nowMs: Long): Long {
        if (lastBytes >= 0 && nowMs > lastMs) {
            val instant = (bytes - lastBytes) * 1000.0 / (nowMs - lastMs)
            speed = if (speed == 0.0) instant else speed * (1 - SMOOTHING) + instant * SMOOTHING
        }
        lastBytes = bytes
        lastMs = nowMs
        return speed.toLong().coerceAtLeast(0)
    }

    private companion object {
        const val SMOOTHING = 0.3
    }
}
