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

/**
 * Keeps parts as `<partsDir>/<taskId>.part<N>` files and hidden files in [privateDir]. Subclasses
 * decide where finished files go.
 */
abstract class FileDownloadStorage(
    private val partsDir: Path,
    private val privateDir: Path = Path(partsDir, "private"),
) : DownloadStorage {
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

    override fun localPartPath(taskId: String, part: Int): String {
        fs.createDirectories(partsDir)
        return partPath(taskId, part).toString()
    }

    override suspend fun appendPart(fromTaskId: String, fromPart: Int, toTaskId: String, toPart: Int) = withContext(ioDispatcher) {
        fs.createDirectories(partsDir)
        val from = partPath(fromTaskId, fromPart)
        val to = partPath(toTaskId, toPart)
        if (!fs.exists(to) && runCatching { fs.atomicMove(from, to) }.isSuccess) return@withContext
        fs.sink(to, append = true).buffered().use { out -> fs.source(from).buffered().use { it.transferTo(out) } }
        fs.delete(from, mustExist = false)
    }

    override suspend fun importPart(uri: String, taskId: String, part: Int): Boolean = withContext(ioDispatcher) {
        fs.createDirectories(partsDir)
        runCatching { copyFromUri(uri, partPath(taskId, part)) }.isSuccess
    }

    override suspend fun deleteFile(uri: String): Boolean = withContext(ioDispatcher) { runCatching { deleteUri(uri) }.getOrDefault(false) }

    override suspend fun renameFile(uri: String, newFileName: String): String? = withContext(ioDispatcher) {
        val path = pathOf(uri) ?: return@withContext null
        val target = Path(path.parent ?: return@withContext null, newFileName)
        if (fs.exists(target)) return@withContext null
        runCatching { fs.atomicMove(path, target) }.getOrNull() ?: return@withContext null
        uriOf(target)
    }

    override suspend fun hide(task: DownloadTask): String? = withContext(ioDispatcher) {
        val uri = task.localUri ?: return@withContext null
        fs.createDirectories(privateDir)
        val ext = task.fileName.substringAfterLast('.', "")
        val target = Path(privateDir, if (ext.isEmpty()) task.id else "${task.id}.$ext")
        copyFromUri(uri, target)
        deleteUri(uri)
        uriOf(target)
    }

    override suspend fun unhide(task: DownloadTask, appFolder: String, conflict: ConflictPolicy): String? {
        val source = pathOf(task.localUri ?: return null) ?: return null
        withContext(ioDispatcher) {
            fs.createDirectories(partsDir)
            val part = partPath(task.id, 0)
            runCatching { fs.atomicMove(source, part) }.getOrElse {
                fs.sink(part).buffered().use { out -> fs.source(source).buffered().use { it.transferTo(out) } }
                fs.delete(source, mustExist = false)
            }
        }
        return publish(task, appFolder, conflict).uri
    }

    /** The local file behind a `file:` uri, or null for other schemes. */
    protected open fun pathOf(uri: String): Path? {
        val raw = when {
            uri.startsWith("file://") -> uri.removePrefix("file://")
            uri.startsWith("file:") -> uri.removePrefix("file:")
            uri.startsWith("/") -> uri
            else -> return null
        }
        return Path(percentDecode(raw))
    }

    /** The uri published for a local file. */
    protected open fun uriOf(path: Path): String = "file://$path"

    /** Copies the file behind [uri] to [target]. */
    protected open suspend fun copyFromUri(uri: String, target: Path) {
        val source = pathOf(uri) ?: error("Unsupported uri: $uri")
        fs.sink(target).buffered().use { out -> fs.source(source).buffered().use { it.transferTo(out) } }
    }

    /** Deletes the file behind [uri]. */
    protected open suspend fun deleteUri(uri: String): Boolean {
        val path = pathOf(uri) ?: return false
        if (!fs.exists(path)) return false
        fs.delete(path)
        return true
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

private fun percentDecode(text: String): String {
    if ('%' !in text) return text
    val bytes = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 2 <= text.lastIndex) {
            val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                bytes += hex.toByte()
                i += 3
                continue
            }
        }
        c.toString().encodeToByteArray().forEach { bytes += it }
        i++
    }
    return bytes.toByteArray().decodeToString()
}
