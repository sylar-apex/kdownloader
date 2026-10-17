package org.app.kdownloader.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.app.kdownloader.merge.MergeInput
import org.app.kdownloader.merge.MergeJob
import org.app.kdownloader.merge.Merger
import org.app.kdownloader.model.DownloadPart
import org.app.kdownloader.model.DownloadTask
import org.app.kdownloader.platform.DownloadStorage

/** The running task as the [PartRunner] sees it; every change goes through the downloader. */
internal interface TaskHandle {
    fun current(): DownloadTask

    /** Applies [transform] to the task and saves it. */
    suspend fun update(transform: (DownloadTask) -> DownloadTask)

    /** Asks the app's [org.app.kdownloader.UrlRefresher] for fresh urls; false when there is none or nothing changed. */
    suspend fun refreshUrls(): Boolean

    suspend fun progress(downloaded: Long, total: Long)
}

/** Storage keys of a multi-part task: each part gets its own key, an HLS audio rendition a second one. */
internal object PartKeys {
    fun stream(taskId: String, part: Int) = "$taskId.p$part"
    fun audio(taskId: String, part: Int) = "$taskId.p${part}a"

    fun all(task: DownloadTask): List<String> = task.parts.indices.flatMap { listOf(stream(task.id, it), audio(task.id, it)) }
}

/**
 * Downloads the [DownloadTask.parts] of a task one after the other, each into its own file:
 * - plain parts in fixed [RunOptions.segmentBytes] segments over up to 8 workers, resumable per segment;
 * - parts of unknown size in one request;
 * - HLS parts segment by segment from the best variant (plus its audio rendition).
 * 403/429/5xx are retried with backoff and without the Cookie header; a 403 or 410 first asks for fresh urls.
 */
internal class PartRunner(
    private val fetcher: HttpFetcher,
    private val storage: DownloadStorage,
) {
    suspend fun run(handle: TaskHandle, options: RunOptions): List<MergeInput> {
        val task = handle.current()
        // Sizes from the request can be estimates; the server's Content-Range is authoritative.
        val probed = task.parts.map { p ->
            if (p.hls) p else fetcher.probeRange(p.url, p.headers.withoutCookie()).let { real ->
                if (real > 0 && real != p.size) p.copy(size = real, doneSegments = emptySet()) else p
            }
        }
        handle.update { it.copy(parts = probed, totalBytes = probed.sumOf { p -> maxOf(p.size, 0) }) }
        val inputs = mutableListOf<MergeInput>()
        var finishedBytes = 0L
        for (index in probed.indices) {
            val part = handle.current().parts[index]
            val outputs = when {
                part.hls -> downloadHls(handle, index, finishedBytes, options)
                part.size <= 0 -> downloadWhole(handle, index, finishedBytes, options)
                else -> downloadSegmented(handle, index, finishedBytes, options)
            }
            outputs.forEach { finishedBytes += storage.partSize(it.storageKey, 0) }
            inputs += outputs
        }
        return inputs
    }

    /** Joins [inputs] into part 0 of the task: a lone plain stream is moved, anything else goes through [merger]. */
    suspend fun assemble(task: DownloadTask, inputs: List<MergeInput>, merger: Merger) {
        if (inputs.size == 1 && !inputs[0].hls) {
            storage.deleteParts(task.id)
            storage.appendPart(inputs[0].storageKey, 0, task.id, 0)
        } else {
            merger.merge(MergeJob(task, inputs, storage))
        }
    }

    private fun input(task: DownloadTask, index: Int, key: String, hls: Boolean) =
        MergeInput(index, hls, storage.localPartPath(key, 0), key)

    private suspend fun throttle(options: RunOptions, bytes: Int) = options.limiters.forEach { it.acquire(bytes) }

    private suspend fun downloadSegmented(handle: TaskHandle, index: Int, base: Long, options: RunOptions): List<MergeInput> {
        val task = handle.current()
        val part = task.parts[index]
        val key = PartKeys.stream(task.id, index)
        val seg = options.segmentBytes.coerceAtLeast(1)
        val count = ((part.size + seg - 1) / seg).toInt()
        val lock = Mutex()
        val done = part.doneSegments.filter { it < count }.toMutableSet()
        val pending = ArrayDeque((0 until count).filter { it !in done })
        var bytes = done.sumOf { segLen(part.size, it, seg) }
        var refreshed = false
        handle.progress(base + bytes, -1)

        suspend fun worker() {
            while (true) {
                currentCoroutineContext().ensureActive()
                val s = lock.withLock { pending.removeFirstOrNull() } ?: return
                val start = s.toLong() * seg
                val end = minOf(part.size, start + seg) - 1
                var attempt = 0
                while (true) {
                    var got = 0L
                    try {
                        val current = handle.current().parts[index]
                        val headers = if (attempt > 0) current.headers.withoutCookie() else current.headers
                        val writer = storage.openPart(key, s, append = false)
                        try {
                            fetcher.fetchRange(current.url, headers, start, end, writer, current.allowHtml) { n ->
                                throttle(options, n)
                                got += n
                                handle.progress(base + lock.withLock { bytes += n; bytes }, -1)
                            }
                        } finally {
                            writer.close()
                        }
                        val snapshot = lock.withLock { done += s; done.toSet() }
                        handle.update { t -> t.copy(parts = t.parts.mapIndexed { i, p -> if (i == index) p.copy(doneSegments = snapshot) else p }) }
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        lock.withLock { bytes -= got }
                        val code = (error as? DownloadException)?.httpStatus
                        if (code != null) {
                            if (code in REFRESH_CODES && lock.withLock { !refreshed.also { refreshed = true } } && handle.refreshUrls()) continue
                            // 403/429/5xx are often rate limits (sites alternate 200/403): back off and retry.
                            if (++attempt >= HTTP_ATTEMPTS || code !in RETRY_CODES) throw error
                            delay(HTTP_BACKOFF_MS * attempt)
                        } else {
                            if (++attempt >= IO_ATTEMPTS) throw error
                            delay(IO_BACKOFF_MS * attempt)
                        }
                    }
                }
            }
        }

        val workers = options.connections.coerceIn(1, MAX_WORKERS)
        coroutineScope { repeat(workers) { launch { worker() } } }
        storage.mergeParts(key, count)
        return listOf(input(task, index, key, hls = false))
    }

    private suspend fun downloadWhole(handle: TaskHandle, index: Int, base: Long, options: RunOptions): List<MergeInput> {
        val task = handle.current()
        val key = PartKeys.stream(task.id, index)
        var attempt = 0
        var refreshed = false
        while (true) {
            var got = 0L
            try {
                val part = handle.current().parts[index]
                val writer = storage.openPart(key, 0, append = false)
                try {
                    fetcher.fetchWhole(part.url, if (attempt > 0) part.headers.withoutCookie() else part.headers, writer, part.allowHtml) { n ->
                        throttle(options, n)
                        got += n
                        handle.progress(base + got, -1)
                    }
                } finally {
                    writer.close()
                }
                break
            } catch (error: DownloadException) {
                val code = error.httpStatus ?: throw error
                if (code in REFRESH_CODES && !refreshed) {
                    refreshed = true
                    if (handle.refreshUrls()) continue
                }
                if (code !in RETRY_CODES || ++attempt >= HTTP_ATTEMPTS) throw error
                delay(HTTP_BACKOFF_MS * attempt)
            }
        }
        return listOf(input(task, index, key, hls = false))
    }

    /** Segments of the best variant (and its audio rendition), each kept as a numbered piece until all are done. */
    private suspend fun downloadHls(handle: TaskHandle, index: Int, base: Long, options: RunOptions): List<MergeInput> {
        val task = handle.current()
        val part = task.parts[index]
        var url = part.url
        var text = fetcher.getText(url, part.headers)
        var audioUrl: String? = null
        if (HlsPlaylist.isMaster(text)) {
            val variant = HlsPlaylist.bestVariant(text, url)
            url = variant.url
            text = fetcher.getText(url, part.headers)
            audioUrl = variant.audioUrl
        }
        val videoSegments = HlsPlaylist.segments(text, url)
        val audioSegments = audioUrl?.let { HlsPlaylist.segments(fetcher.getText(it, part.headers), it) }.orEmpty()
        val all = videoSegments + audioSegments
        val videoKey = PartKeys.stream(task.id, index)
        val audioKey = PartKeys.audio(task.id, index)
        fun keyOf(segment: Int) = if (segment < videoSegments.size) videoKey to segment else audioKey to segment - videoSegments.size

        val done = part.doneSegments.filter { it < all.size }.toMutableSet()
        var bytes = done.sumOf { s -> keyOf(s).let { (k, p) -> storage.partSize(k, p) } }
        for ((segment, segmentUrl) in all.withIndex()) {
            if (segment in done) continue
            currentCoroutineContext().ensureActive()
            val (key, piece) = keyOf(segment)
            val writer = storage.openPart(key, piece, append = false)
            try {
                fetcher.fetchWhole(segmentUrl, part.headers, writer, allowHtml = true) { n ->
                    throttle(options, n)
                    bytes += n
                }
            } finally {
                writer.close()
            }
            done += segment
            val snapshot = done.toSet()
            handle.update { t -> t.copy(parts = t.parts.mapIndexed { i, p -> if (i == index) p.copy(doneSegments = snapshot) else p }) }
            // Real bytes so far; the total is estimated from the average segment size.
            val estimate = if (done.size == all.size) bytes else bytes * all.size / done.size
            handle.progress(base + bytes, base + estimate)
        }
        storage.mergeParts(videoKey, videoSegments.size)
        if (audioSegments.isNotEmpty()) storage.mergeParts(audioKey, audioSegments.size)
        return listOfNotNull(
            input(task, index, videoKey, hls = true),
            if (audioSegments.isNotEmpty()) input(task, index, audioKey, hls = true) else null,
        )
    }

    private fun segLen(size: Long, s: Int, seg: Long) = minOf(seg, size - s.toLong() * seg)

    private companion object {
        val RETRY_CODES = setOf(403, 429, 500, 502, 503, 504)
        val REFRESH_CODES = setOf(403, 410)
        const val HTTP_ATTEMPTS = 6
        const val IO_ATTEMPTS = 4
        const val HTTP_BACKOFF_MS = 800L
        const val IO_BACKOFF_MS = 1000L
        const val MAX_WORKERS = 8

        fun Map<String, String>.withoutCookie() = filterKeys { !it.equals("Cookie", ignoreCase = true) }
    }
}
