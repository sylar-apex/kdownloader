package org.app.kdownloader.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.app.kdownloader.model.Destination
import org.app.kdownloader.model.DownloadStatus
import org.app.kdownloader.model.DownloadTask
import org.app.kdownloader.model.DownloaderConfig
import org.app.kdownloader.model.RetryPolicy

class QueuePlannerTest {
    private fun task(id: String, status: DownloadStatus = DownloadStatus.QUEUED, priority: Int = 0, order: Long = 0, notBefore: Long = 0, wifiOnly: Boolean? = null) =
        DownloadTask(id, "https://x/$id", id, Destination(), status, priority = priority, order = order, notBefore = notBefore, wifiOnly = wifiOnly)

    @Test
    fun startsByPriorityThenOrderWithinTheLimit() {
        val tasks = listOf(task("a", order = 1), task("b", order = 2, priority = 3), task("c", order = 0))
        val plan = QueuePlanner.plan(tasks, DownloaderConfig(maxParallel = 2), unmetered = true, now = 0)
        assertEquals(listOf("b", "c"), plan.start.map { it.id })
    }

    @Test
    fun runningTasksTakeSlots() {
        val tasks = listOf(task("r", DownloadStatus.RUNNING), task("q"))
        assertTrue(QueuePlanner.plan(tasks, DownloaderConfig(maxParallel = 1), true, 0).start.isEmpty())
    }

    @Test
    fun scheduledAndWifiTasksWait() {
        val tasks = listOf(task("later", notBefore = 500), task("wifi", wifiOnly = true))
        val plan = QueuePlanner.plan(tasks, DownloaderConfig(), unmetered = false, now = 100)
        assertTrue(plan.start.isEmpty())
        assertEquals(500, plan.nextWakeMs)
        assertTrue(plan.waitingForWifi)
    }

    @Test
    fun segmentsRespectConnectionsAndMinimumPartSize() {
        assertEquals(4, QueuePlanner.segmentsFor(10_000_000, 4, 512_000))
        assertEquals(2, QueuePlanner.segmentsFor(1_100_000, 8, 512_000))
        assertEquals(1, QueuePlanner.segmentsFor(100_000, 8, 512_000))
        assertEquals(16, QueuePlanner.segmentsFor(1_000_000_000, 99, 1))
    }

    @Test
    fun backoffGrowsAndCaps() {
        val policy = RetryPolicy(initialDelayMs = 1_000, maxDelayMs = 5_000, factor = 2.0)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L), (1..4).map(policy::delayFor))
    }
}
