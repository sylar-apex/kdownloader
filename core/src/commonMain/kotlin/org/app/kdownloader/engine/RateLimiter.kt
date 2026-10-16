package org.app.kdownloader.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spaces out bytes so they average at most [rate] bytes per second. Shared by every connection it
 * applies to; a rate of 0 or less lets everything through.
 */
internal class RateLimiter(private val clock: () -> Long, private val rate: () -> Long) {
    private val lock = Mutex()
    private var cursorMs = 0L

    suspend fun acquire(bytes: Int) {
        val limit = rate()
        if (limit <= 0) return
        val waitMs = lock.withLock {
            val now = clock()
            cursorMs = maxOf(cursorMs, now) + bytes * 1000L / limit
            cursorMs - now
        }
        if (waitMs > 0) delay(waitMs)
    }
}
