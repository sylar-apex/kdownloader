package org.app.kdownloader

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.app.kdownloader.model.Destination
import org.app.kdownloader.model.DownloadRequest
import org.app.kdownloader.model.DownloadStatus
import org.app.kdownloader.model.DownloadTask
import org.app.kdownloader.model.ConflictPolicy
import org.app.kdownloader.model.DownloaderConfig
import org.app.kdownloader.model.RetryPolicy
import org.app.kdownloader.model.EnqueueResult
import org.app.kdownloader.model.MediaType
import org.app.kdownloader.platform.DesktopDownloadStorage
import org.app.kdownloader.platform.NetworkMonitor
import org.app.kdownloader.store.MemoryTaskStore

class KDownloaderTest {
    private val body = Random(7).nextBytes(1_200_000)
    private val server = TestServer(body)
    private val home = Files.createTempDirectory("kd-home").toFile()
    private val parts = Files.createTempDirectory("kd-parts").toFile()
    private val opened = mutableListOf<KDownloader>()

    @AfterTest
    fun tearDown() {
        opened.forEach { it.close() }
        server.close()
        home.deleteRecursively()
        parts.deleteRecursively()
    }

    private fun downloader(
        config: DownloaderConfig = DownloaderConfig(appFolder = "Test"),
        store: MemoryTaskStore = MemoryTaskStore(),
        unmetered: Boolean = true,
    ) = KDownloader(config, store, DesktopDownloadStorage(parts, home), NetworkMonitor { unmetered }).also { opened += it }

    private fun request(path: String = "file.mp4") = DownloadRequest(server.url(path), "clip.mp4", Destination(MediaType.VIDEO))

    private fun KDownloader.added(request: DownloadRequest) = assertIs<EnqueueResult.Added>(enqueue(request)).task.id

    private fun KDownloader.added(path: String = "file.mp4") = assertIs<EnqueueResult.Added>(enqueue(request(path))).task.id

    private fun KDownloader.await(id: String, predicate: (DownloadTask) -> Boolean): DownloadTask = runBlocking {
        withTimeout(TIMEOUT_MS) { tasks.first { list -> list.any { it.id == id && predicate(it) } } }.first { it.id == id }
    }

    private fun KDownloader.awaitStatus(id: String, status: DownloadStatus) = await(id) { it.status == status }

    private fun savedFile() = File(home, "Movies/Test/clip.mp4")

    @Test
    fun downloadsAFileAndPublishesIt() {
        val kd = downloader()
        val done = kd.awaitStatus(kd.added(), DownloadStatus.COMPLETED)
        assertContentEquals(body, savedFile().readBytes())
        assertEquals(body.size.toLong(), done.totalBytes)
        assertEquals(savedFile().toURI().toString(), done.localUri)
        assertTrue(parts.listFiles().isNullOrEmpty())
    }

    @Test
    fun splitsLargeFilesIntoRangedParts() {
        val kd = downloader(DownloaderConfig(appFolder = "Test", connections = 3, minSegmentBytes = 100_000))
        val done = kd.awaitStatus(kd.added(), DownloadStatus.COMPLETED)
        assertEquals(3, done.segments)
        assertEquals(3, server.ranges.size)
        assertContentEquals(body, savedFile().readBytes())
    }

    @Test
    fun pauseKeepsTheBytesAndResumeContinues() {
        val kd = downloader()
        val id = kd.added("slow/file.mp4")
        kd.await(id) { it.downloadedBytes > 0 }
        kd.pause(id)
        val paused = kd.awaitStatus(id, DownloadStatus.PAUSED)
        assertTrue(paused.downloadedBytes in 1 until body.size)
        kd.resume(id)
        kd.awaitStatus(id, DownloadStatus.COMPLETED)
        assertTrue(server.ranges.any { !it.startsWith("bytes=0-") })
        assertContentEquals(body, savedFile().readBytes())
    }

    @Test
    fun theSameUrlIsNotQueuedTwice() {
        val kd = downloader(DownloaderConfig(appFolder = "Test", maxParallel = 1))
        kd.added("slow/file.mp4")
        assertIs<EnqueueResult.Duplicate>(kd.enqueue(request("slow/file.mp4")))
    }

    @Test
    fun respectsTheParallelLimit() {
        val kd = downloader(DownloaderConfig(appFolder = "Test", maxParallel = 1))
        val first = kd.added("slow/a.mp4")
        val second = kd.added("slow/b.mp4")
        kd.awaitStatus(first, DownloadStatus.RUNNING)
        assertEquals(DownloadStatus.QUEUED, kd.tasks.value.first { it.id == second }.status)
        kd.cancel(first)
        kd.awaitStatus(second, DownloadStatus.RUNNING)
    }

    @Test
    fun wifiOnlyWaitsOnMeteredNetworks() = runBlocking<Unit> {
        val kd = downloader(DownloaderConfig(appFolder = "Test", wifiOnly = true), unmetered = false)
        val id = kd.added()
        withTimeout(TIMEOUT_MS) { kd.waitingForWifi.first { it } }
        assertEquals(DownloadStatus.QUEUED, kd.tasks.value.first { it.id == id }.status)
    }

    @Test
    fun interruptedDownloadsComeBackQueuedOrPaused() {
        val running = DownloadTask("dl-1", server.url("file.mp4"), "clip.mp4", Destination(MediaType.VIDEO), DownloadStatus.RUNNING)
        val paused = downloader(DownloaderConfig(appFolder = "Test", autoResume = false), MemoryTaskStore().apply { save(listOf(running)) })
        assertEquals(DownloadStatus.PAUSED, paused.tasks.value.single().status)
        val resumed = downloader(DownloaderConfig(appFolder = "Test", autoResume = true), MemoryTaskStore().apply { save(listOf(running)) })
        resumed.awaitStatus("dl-1", DownloadStatus.COMPLETED)
    }

    @Test
    fun cancelDeletesThePartialFile() = runBlocking<Unit> {
        val kd = downloader()
        val id = kd.added("slow/file.mp4")
        kd.await(id) { it.downloadedBytes > 0 }
        kd.cancel(id)
        kd.awaitStatus(id, DownloadStatus.CANCELED)
        withTimeout(TIMEOUT_MS) { while (!parts.listFiles().isNullOrEmpty()) delay(20) }
    }

    @Test
    fun httpErrorsFailTheTask() {
        val kd = downloader()
        val failed = kd.awaitStatus(kd.added("missing.mp4"), DownloadStatus.FAILED)
        assertEquals("HTTP 404", failed.error)
    }

    @Test
    fun pauseAllResumeAllAndRemove() = runBlocking<Unit> {
        val kd = downloader(DownloaderConfig(appFolder = "Test", maxParallel = 1))
        val a = kd.added("slow/a.mp4")
        kd.added("slow/b.mp4")
        kd.awaitStatus(a, DownloadStatus.RUNNING)
        assertEquals(2, kd.pauseAll())
        kd.awaitStatus(a, DownloadStatus.PAUSED)
        assertEquals(2, kd.resumeAll())
        kd.remove(kd.tasks.value.map { it.id })
        withTimeout(TIMEOUT_MS) { kd.tasks.first { it.isEmpty() } }
    }

    @Test
    fun smallFilesAreNotSplitBelowTheMinimumPart() {
        val kd = downloader(DownloaderConfig(appFolder = "Test", connections = 8))
        assertEquals(2, kd.awaitStatus(kd.added(), DownloadStatus.COMPLETED).segments)
    }

    @Test
    fun higherPriorityStartsFirst() {
        val kd = downloader(DownloaderConfig(appFolder = "Test", maxParallel = 1))
        val blocker = kd.added("slow/blocker.mp4")
        kd.awaitStatus(blocker, DownloadStatus.RUNNING)
        val low = kd.added(request("slow/low.mp4"))
        val high = kd.added(request("slow/high.mp4").copy(priority = 5))
        kd.pause(blocker)
        kd.awaitStatus(high, DownloadStatus.RUNNING)
        assertEquals(DownloadStatus.QUEUED, kd.tasks.value.first { it.id == low }.status)
    }

    @Test
    fun moveToTopReordersEqualPriorities() {
        val kd = downloader(DownloaderConfig(appFolder = "Test", maxParallel = 1))
        val blocker = kd.added("slow/blocker.mp4")
        kd.awaitStatus(blocker, DownloadStatus.RUNNING)
        kd.added("slow/first.mp4")
        val last = kd.added("slow/last.mp4")
        kd.moveToTop(last)
        kd.pause(blocker)
        kd.awaitStatus(last, DownloadStatus.RUNNING)
    }

    @Test
    fun scheduledTasksWaitUntilTheirTime() {
        val kd = downloader()
        val at = System.currentTimeMillis() + 600
        val id = kd.added(request().copy(notBefore = at))
        Thread.sleep(200)
        assertEquals(DownloadStatus.QUEUED, kd.tasks.value.first { it.id == id }.status)
        kd.awaitStatus(id, DownloadStatus.COMPLETED)
        assertTrue(System.currentTimeMillis() >= at)
    }

    @Test
    fun serverErrorsAreRetriedWithBackoff() {
        val kd = downloader(DownloaderConfig(appFolder = "Test", retry = RetryPolicy(maxAttempts = 3, initialDelayMs = 50)))
        val done = kd.awaitStatus(kd.added("flaky/file.mp4"), DownloadStatus.COMPLETED)
        assertEquals(2, done.attempts)
        assertContentEquals(body, savedFile().readBytes())
    }

    @Test
    fun retriesStopAtTheLimit() {
        val kd = downloader(DownloaderConfig(appFolder = "Test", retry = RetryPolicy(maxAttempts = 1, initialDelayMs = 50)))
        val failed = kd.awaitStatus(kd.added("flaky/file.mp4"), DownloadStatus.FAILED)
        assertEquals("HTTP 503", failed.error)
    }

    @Test
    fun speedLimitSlowsTheDownload() {
        val kd = downloader(DownloaderConfig(appFolder = "Test", speedLimitBytesPerSec = 1_000_000))
        val started = System.currentTimeMillis()
        kd.awaitStatus(kd.added(), DownloadStatus.COMPLETED)
        assertTrue(System.currentTimeMillis() - started >= 1_000, "1.2 MB at 1 MB/s takes at least a second")
    }

    @Test
    fun takenNamesAreRenamedOrSkipped() {
        savedFile().apply { parentFile.mkdirs(); writeText("old") }
        val renaming = downloader()
        val renamed = renaming.awaitStatus(renaming.added(), DownloadStatus.COMPLETED)
        assertTrue(renamed.localUri!!.endsWith("clip%20(2).mp4"))
        assertEquals("old", savedFile().readText())
        val skipping = downloader(DownloaderConfig(appFolder = "Test", conflict = ConflictPolicy.SKIP))
        skipping.awaitStatus(skipping.added("file.mp4?again"), DownloadStatus.COMPLETED)
        assertEquals("old", savedFile().readText())
    }

    @Test
    fun customHeadersAreSent() {
        val kd = downloader()
        kd.awaitStatus(kd.added(request().copy(headers = mapOf("X-Test" to "hello"))), DownloadStatus.COMPLETED)
        assertTrue("hello" in server.customHeaders)
    }

    @Test
    fun tagsControlGroups() {
        val kd = downloader(DownloaderConfig(appFolder = "Test", maxParallel = 4))
        val a = kd.added(request("slow/a.mp4").copy(tags = setOf("music")))
        val b = kd.added(request("slow/b.mp4").copy(tags = setOf("video")))
        kd.awaitStatus(a, DownloadStatus.RUNNING)
        assertEquals(1, kd.pauseTag("music"))
        kd.awaitStatus(a, DownloadStatus.PAUSED)
        assertTrue(kd.tasks.value.first { it.id == b }.status != DownloadStatus.PAUSED)
        assertEquals(listOf(a), kd.tasksWithTag("music").map { it.id })
    }

    @Test
    fun speedAndEtaAreReported() {
        val kd = downloader(DownloaderConfig(appFolder = "Test", progressIntervalMs = 50))
        val id = kd.added("slow/file.mp4")
        val running = kd.await(id) { it.speedBytesPerSec > 0 }
        assertTrue(running.etaMs >= 0)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
