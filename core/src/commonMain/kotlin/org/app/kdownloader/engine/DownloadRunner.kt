package org.app.kdownloader.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.app.kdownloader.model.DownloadTask
import org.app.kdownloader.platform.DownloadStorage

/** Progress of a running task: bytes on disk so far and the full size (0 while unknown). */
internal fun interface ProgressSink {
    suspend fun update(downloaded: Long, total: Long, segments: Int)
}

/** How one task is fetched: connections wanted, minimum part size and the speed limiters it passes through. */
internal class RunOptions(
    val connections: Int,
    val minSegmentBytes: Long,
    val limiters: List<RateLimiter>,
)

/**
 * Downloads one task into [storage], continuing whatever parts already exist. A fresh task is split
 * into ranged parts when the server supports ranges and the file is large enough.
 */
internal class DownloadRunner(
    private val fetcher: HttpFetcher,
    private val storage: DownloadStorage,
) {
    suspend fun run(task: DownloadTask, options: RunOptions, progress: ProgressSink): Long {
        val planned = chooseSegments(task, options)
        return if (planned.segments > 1) runSegmented(planned, options, progress) else runSingle(task, options, progress)
    }

    private suspend fun chooseSegments(task: DownloadTask, options: RunOptions): DownloadTask {
        if (task.segments > 1) return task
        if (options.connections <= 1 || storage.partSize(task.id, 0) > 0) return task
        val probe = fetcher.probe(task.url, task.headers)
        if (!probe.acceptsRanges) return task
        val segments = QueuePlanner.segmentsFor(probe.totalBytes, options.connections, options.minSegmentBytes)
        return task.copy(segments = segments, totalBytes = probe.totalBytes)
    }

    private suspend fun throttle(options: RunOptions, bytes: Int) = options.limiters.forEach { it.acquire(bytes) }

    private suspend fun runSingle(task: DownloadTask, options: RunOptions, progress: ProgressSink): Long {
        var written = storage.partSize(task.id, 0)
        var total = task.totalBytes
        val result = fetcher.fetch(
            url = task.url,
            headers = task.headers,
            from = written,
            to = null,
            open = { append ->
                if (!append) written = 0
                storage.openPart(task.id, 0, append)
            },
            onBytes = { read ->
                throttle(options, read)
                written += read
                progress.update(written, total, 1)
            },
            onTotal = { total = it },
        )
        if (result > 0) total = result
        progress.update(written, total, 1)
        return written
    }

    /** Each part i covers its own fixed byte range, so paused parts resume where they stopped. */
    private suspend fun runSegmented(task: DownloadTask, options: RunOptions, progress: ProgressSink): Long {
        val total = task.totalBytes.takeIf { it > 0 } ?: fetcher.probe(task.url, task.headers).totalBytes
        if (total <= 0) throw DownloadException("Unknown file size")
        val count = task.segments
        val sizes = LongArray(count) { storage.partSize(task.id, it) }
        val lock = Mutex()
        progress.update(sizes.sum(), total, count)
        coroutineScope {
            (0 until count).map { part ->
                async {
                    val start = total * part / count
                    val end = total * (part + 1) / count - 1
                    val from = start + sizes[part]
                    if (from > end) return@async
                    fetcher.fetch(
                        url = task.url,
                        headers = task.headers,
                        from = from,
                        to = end,
                        open = { append -> storage.openPart(task.id, part, append) },
                        onBytes = { read ->
                            throttle(options, read)
                            val sum = lock.withLock {
                                sizes[part] += read
                                sizes.sum()
                            }
                            progress.update(sum, total, count)
                        },
                    )
                }
            }.awaitAll()
        }
        storage.mergeParts(task.id, count)
        return total
    }
}
