package org.app.kdownloader.platform

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.app.kdownloader.model.ConflictPolicy
import org.app.kdownloader.model.Destination
import org.app.kdownloader.model.DownloadTask

/** Keeps parts as `<partsDir>/<taskId>.part<N>` files. Subclasses decide where finished files go. */
abstract class FileDownloadStorage(private val partsDir: Path) : DownloadStorage {
    private val fs = SystemFileSystem

    protected fun partPath(taskId: String, part: Int) = Path(partsDir, "$taskId.part$part")

    override suspend fun partSize(taskId: String, part: Int): Long = withContext(ioDispatcher) {
        fs.metadataOrNull(partPath(taskId, part))?.size ?: 0L
    }

    override suspend fun openPart(taskId: String, part: Int, append: Boolean): PartWriter = withContext(ioDispatcher) {
        fs.createDirectories(partsDir)
        SinkWriter(fs.sink(partPath(taskId, part), append).buffered())
    }

    override suspend fun deleteParts(taskId: String) = withContext(ioDispatcher) {
        fs.list(partsDir.takeIf { fs.exists(it) } ?: return@withContext)
            .filter { it.name.startsWith("$taskId.part") }
            .forEach { fs.delete(it, mustExist = false) }
    }

    override suspend fun mergeParts(taskId: String, count: Int) = withContext(ioDispatcher) {
        fs.sink(partPath(taskId, 0), append = true).buffered().use { out ->
            for (part in 1 until count) {
                val path = partPath(taskId, part)
                fs.source(path).buffered().use { it.transferTo(out) }
            }
        }
        for (part in 1 until count) fs.delete(partPath(taskId, part), mustExist = false)
    }

    /** The file to write in [dir], or null when [conflict] is SKIP and the name is taken (returned as [existing]). */
    protected fun resolveTarget(dir: Path, fileName: String, conflict: ConflictPolicy): Pair<Path, Boolean> {
        val first = Path(dir, fileName)
        if (!fs.exists(first)) return first to false
        return when (conflict) {
            ConflictPolicy.OVERWRITE -> first to false
            ConflictPolicy.SKIP -> first to true
            ConflictPolicy.RENAME -> generateSequence(2) { it + 1 }.map { Path(dir, numberedName(fileName, it)) }.first { !fs.exists(it) } to false
        }
    }

    /** Copies part 0 to [target], replacing an older file of the same name. */
    protected suspend fun movePartTo(task: DownloadTask, target: Path) = withContext(ioDispatcher) {
        target.parent?.let { fs.createDirectories(it) }
        runCatching { fs.atomicMove(partPath(task.id, 0), target) }.getOrElse {
            fs.sink(target).buffered().use { out -> fs.source(partPath(task.id, 0)).buffered().use { it.transferTo(out) } }
            fs.delete(partPath(task.id, 0), mustExist = false)
        }
    }

    abstract override suspend fun publish(task: DownloadTask, appFolder: String, conflict: ConflictPolicy): Published
    abstract override fun locationOf(destination: Destination, appFolder: String): String

    private class SinkWriter(private val sink: Sink) : PartWriter {
        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) = withContext(ioDispatcher) {
            sink.write(bytes, offset, offset + length)
        }

        /** Runs even when the download was cancelled, so paused parts keep every byte received. */
        override suspend fun close() = withContext(NonCancellable + ioDispatcher) { sink.close() }
    }
}
