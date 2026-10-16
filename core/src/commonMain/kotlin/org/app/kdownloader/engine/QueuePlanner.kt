package org.app.kdownloader.engine

import org.app.kdownloader.model.DownloadStatus
import org.app.kdownloader.model.DownloadTask
import org.app.kdownloader.model.DownloaderConfig

/** Which queued tasks may start now, and when to look again. Pure, so it is easy to test. */
internal object QueuePlanner {
    /** Queue order: higher priority first, then the order tasks were added or moved to. */
    val queueOrder: Comparator<DownloadTask> = compareByDescending<DownloadTask> { it.priority }.thenBy { it.order }

    data class Plan(
        val start: List<DownloadTask>,
        /** Some tasks wait for Wi-Fi. */
        val waitingForWifi: Boolean,
        /** Earliest time a scheduled or backing-off task becomes ready, or null. */
        val nextWakeMs: Long?,
    )

    fun plan(tasks: List<DownloadTask>, config: DownloaderConfig, unmetered: Boolean, now: Long): Plan {
        var running = tasks.count { it.status == DownloadStatus.RUNNING }
        val queued = tasks.filter { it.status == DownloadStatus.QUEUED }.sortedWith(queueOrder)
        val start = mutableListOf<DownloadTask>()
        var waiting = false
        var wake: Long? = null
        for (task in queued) {
            if (task.notBefore > now) {
                wake = minOf(wake ?: task.notBefore, task.notBefore)
                continue
            }
            if ((task.wifiOnly ?: config.wifiOnly) && !unmetered) {
                waiting = true
                continue
            }
            if (running >= config.parallel) continue
            running++
            start += task
        }
        return Plan(start, waiting, wake)
    }

    /** Ranged parts for a file of [totalBytes]: at most [connections], each at least [minSegmentBytes]. */
    fun segmentsFor(totalBytes: Long, connections: Int, minSegmentBytes: Long): Int {
        val wanted = connections.coerceIn(1, DownloaderConfig.MAX_CONNECTIONS)
        if (wanted == 1 || totalBytes <= 0) return 1
        val bySize = (totalBytes / minSegmentBytes.coerceAtLeast(1)).toInt()
        return wanted.coerceAtMost(bySize).coerceAtLeast(1)
    }
}
