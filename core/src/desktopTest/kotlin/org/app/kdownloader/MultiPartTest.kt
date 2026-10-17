package org.app.kdownloader

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.app.kdownloader.merge.FallbackMerger
import org.app.kdownloader.merge.Merger
import org.app.kdownloader.model.Destination
import org.app.kdownloader.model.DownloadPart
import org.app.kdownloader.model.DownloadRequest
import org.app.kdownloader.model.DownloadStatus
import org.app.kdownloader.model.DownloadTask
import org.app.kdownloader.model.DownloaderConfig
import org.app.kdownloader.model.EnqueueResult
import org.app.kdownloader.model.MediaType
import org.app.kdownloader.model.TaskExtras
import org.app.kdownloader.model.title
import org.app.kdownloader.platform.DesktopDownloadStorage
import org.app.kdownloader.platform.NetworkMonitor
import org.app.kdownloader.store.MemoryTaskStore

class MultiPartTest {
    private val body = Random(11).nextBytes(1_000_000)
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
        config: DownloaderConfig = DownloaderConfig(appFolder = "Test", segmentBytes = 100_000, connections = 3),
        merger: Merger = FallbackMerger(),
        refresher: UrlRefresher? = null,
    ) = KDownloader(config, MemoryTaskStore(), DesktopDownloadStorage(parts, home), NetworkMonitor { true }, merger = merger, urlRefresher = refresher)
        .also { opened += it }

    private fun KDownloader.added(vararg p: DownloadPart, extras: Map<String, String> = emptyMap()) =
        assertIs<EnqueueResult.Added>(enqueue(DownloadRequest("", "clip.mp4", Destination(MediaType.VIDEO), parts = p.toList(), extras = extras))).task.id

    private fun KDownloader.await(id: String, predicate: (DownloadTask) -> Boolean): DownloadTask = runBlocking {
        withTimeout(TIMEOUT_MS) { tasks.first { list -> list.any { it.id == id && predicate(it) } } }.first { it.id == id }
    }

    private fun KDownloader.awaitStatus(id: String, status: DownloadStatus) = await(id) { it.status == status }

    private fun saved() = File(home, "Movies/Test/clip.mp4")

    @Test
    fun aSinglePartDownloadsInFixedSegments() {
        val kd = downloader()
        val done = kd.awaitStatus(kd.added(DownloadPart(server.url("file.mp4"))), DownloadStatus.COMPLETED)
        assertContentEquals(body, saved().readBytes())
        assertEquals((0 until 10).toSet(), done.parts.single().doneSegments)
        assertEquals(body.size.toLong(), done.parts.single().size)
        assertTrue(done.finishedAt > 0)
        assertTrue(parts.listFiles().isNullOrEmpty())
    }

    @Test
    fun severalPartsGoThroughTheMergerAndShowMerging() {
        val merged = mutableListOf<Int>()
        var sawMerging = false
        lateinit var kd: KDownloader
        kd = downloader(merger = { job ->
            sawMerging = kd.tasks.value.first { it.id == job.task.id }.status == DownloadStatus.MERGING
            merged += job.inputs.size
            assertTrue(job.inputs.all { File(it.path!!).length() == body.size.toLong() })
            job.concatenate()
        })
        kd.awaitStatus(kd.added(DownloadPart(server.url("video.mp4")), DownloadPart(server.url("audio.m4a"))), DownloadStatus.COMPLETED)
        assertEquals(listOf(2), merged)
        assertTrue(sawMerging)
        assertContentEquals(body + body, saved().readBytes())
    }

    @Test
    fun theFallbackMergerKeepsTheFirstStream() {
        val kd = downloader()
        kd.awaitStatus(kd.added(DownloadPart(server.url("video.mp4")), DownloadPart(server.url("audio.m4a"), size = 5)), DownloadStatus.COMPLETED)
        assertContentEquals(body, saved().readBytes())
    }

    @Test
    fun pausedPartsResumeSegmentBySegment() {
        val kd = downloader(DownloaderConfig(appFolder = "Test", segmentBytes = 100_000, connections = 1))
        val id = kd.added(DownloadPart(server.url("slow/file.mp4")))
        kd.await(id) { it.parts.single().doneSegments.size >= 2 }
        kd.pause(id)
        val paused = kd.awaitStatus(id, DownloadStatus.PAUSED)
        val kept = paused.parts.single().doneSegments
        server.ranges.clear()
        kd.resume(id)
        kd.awaitStatus(id, DownloadStatus.COMPLETED)
        assertContentEquals(body, saved().readBytes())
        kept.forEach { s -> assertFalse("bytes=${s * 100_000}-${(s + 1) * 100_000 - 1}" in server.ranges, "segment $s fetched again") }
    }

    @Test
    fun unknownSizesDownloadInOneRequest() {
        val kd = downloader()
        kd.awaitStatus(kd.added(DownloadPart(server.url("seg/1"))), DownloadStatus.COMPLETED)
        assertContentEquals(body.copyOfRange(250_000, 500_000), saved().readBytes())
    }

    @Test
    fun expiredUrlsAreRefreshed() {
        var asked = 0
        val kd = downloader(refresher = { task ->
            asked++
            task.parts.map { it.copy(url = server.url("file.mp4")) }
        })
        val done = kd.awaitStatus(kd.added(DownloadPart(server.url("expired/file.mp4"), size = body.size.toLong(), key = "137")), DownloadStatus.COMPLETED)
        assertEquals(1, asked)
        assertEquals(server.url("file.mp4"), done.parts.single().url)
        assertContentEquals(body, saved().readBytes())
    }

    @Test
    fun rejectedCookiesAreDroppedOnRetry() {
        val kd = downloader(DownloaderConfig(appFolder = "Test", segmentBytes = 2_000_000))
        kd.awaitStatus(kd.added(DownloadPart(server.url("cookiewall/file.mp4"), headers = mapOf("Cookie" to "a=b"))), DownloadStatus.COMPLETED)
        assertContentEquals(body, saved().readBytes())
    }

    @Test
    fun hlsPicksTheBestVariantAndJoinsItsSegments() {
        server.texts["/hls/master.m3u8"] = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=320x180
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=900000,AVERAGE-BANDWIDTH=1,RESOLUTION=1280x720
            high/index.m3u8
        """.trimIndent()
        server.texts["/hls/high/index.m3u8"] = "#EXTM3U\n#EXTINF:4,\n/seg/0.ts\n#EXTINF:4,\n../../seg/1.ts\n#EXTINF:4,\n${server.url("seg/2.ts")}\n#EXTINF:4,\n/seg/3.ts\n#EXT-X-ENDLIST\n"
        val kd = downloader()
        val done = kd.awaitStatus(kd.added(DownloadPart(server.url("hls/master.m3u8"), hls = true)), DownloadStatus.COMPLETED)
        assertContentEquals(body, saved().readBytes())
        assertEquals(setOf(0, 1, 2, 3), done.parts.single().doneSegments)
        assertFalse("/hls/low/index.m3u8" in server.requestedPaths)
    }

    @Test
    fun renameHideAndDelete() = runBlocking {
        val kd = downloader()
        val id = kd.added(DownloadPart(server.url("file.mp4")), extras = mapOf(TaskExtras.TITLE to "clip"))
        kd.awaitStatus(id, DownloadStatus.COMPLETED)
        kd.rename(id, "renamed")
        val renamed = kd.await(id) { it.fileName == "renamed.mp4" }
        assertEquals("renamed", renamed.title)
        val file = File(home, "Movies/Test/renamed.mp4")
        assertTrue(file.exists() && !saved().exists())

        kd.setHidden(id, true).join()
        val hidden = kd.tasks.value.first { it.id == id }
        assertTrue(hidden.hidden)
        assertFalse(file.exists())
        assertTrue(hidden.localUri!!.contains(".kdownloader/private"))

        kd.setHidden(id, false).join()
        assertTrue(file.exists())
        assertFalse(kd.tasks.value.first { it.id == id }.hidden)

        kd.delete(id, withFile = true)
        withTimeout(TIMEOUT_MS) { kd.tasks.first { list -> list.none { it.id == id } } }
        withTimeout(TIMEOUT_MS) { while (file.exists()) delay(20) }
    }

    @Test
    fun convertToMusicIsUnsupportedOnDesktop() = runBlocking {
        val kd = downloader()
        val id = kd.added(DownloadPart(server.url("file.mp4")))
        kd.awaitStatus(id, DownloadStatus.COMPLETED)
        assertNull(kd.convertToMusic(id))
    }

    @Test
    fun convertToMusicUsesTheAudioExtractor() = runBlocking {
        val kd = KDownloader(
            DownloaderConfig(appFolder = "Test"), MemoryTaskStore(), DesktopDownloadStorage(parts, home), NetworkMonitor { true },
            audioExtractor = { input, output -> File(output).writeBytes(File(input).readBytes().copyOf(10)) },
        ).also { opened += it }
        val id = kd.added(DownloadPart(server.url("file.mp4")), extras = mapOf(TaskExtras.TITLE to "song", TaskExtras.QUALITY to "720p"))
        kd.awaitStatus(id, DownloadStatus.COMPLETED)
        val music = assertNotNull(kd.convertToMusic(id))
        assertEquals("song.m4a", music.fileName)
        assertEquals(MediaType.AUDIO, music.destination.type)
        assertNull(music.extras[TaskExtras.QUALITY])
        assertContentEquals(body.copyOf(10), File(home, "Music/Test/song.m4a").readBytes())
        assertEquals(2, kd.tasks.value.size)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
