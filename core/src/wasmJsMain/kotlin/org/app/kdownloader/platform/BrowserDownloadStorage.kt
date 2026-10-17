package org.app.kdownloader.platform

import org.app.kdownloader.model.ConflictPolicy
import org.app.kdownloader.model.Destination
import org.app.kdownloader.model.DownloadTask
import org.khronos.webgl.Int8Array
import org.khronos.webgl.set

@JsFun("() => []")
private external fun newChunkList(): JsAny

@JsFun("(list, chunk) => list.push(chunk)")
private external fun pushChunk(list: JsAny, chunk: Int8Array)

@JsFun("(list, mime) => URL.createObjectURL(new Blob(list, { type: mime }))")
private external fun blobUrl(list: JsAny, mime: String): String

@JsFun("(url, name) => { const a = document.createElement('a'); a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove(); }")
private external fun saveAs(url: String, name: String)

/**
 * Browsers cannot write files directly: parts are kept in memory, and a finished download is handed
 * to the browser's own download manager. Partial downloads do not survive a page reload.
 */
class BrowserDownloadStorage : DownloadStorage {
    private class Part(val chunks: JsAny = newChunkList(), var size: Long = 0)

    private val parts = mutableMapOf<String, MutableMap<Int, Part>>()

    override suspend fun partSize(taskId: String, part: Int): Long = parts[taskId]?.get(part)?.size ?: 0

    override suspend fun openPart(taskId: String, part: Int, append: Boolean): PartWriter {
        val map = parts.getOrPut(taskId) { mutableMapOf() }
        val target = if (append) map.getOrPut(part) { Part() } else Part().also { map[part] = it }
        return object : PartWriter {
            override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
                val chunk = Int8Array(length)
                for (i in 0 until length) chunk[i] = bytes[offset + i]
                pushChunk(target.chunks, chunk)
                target.size += length
            }

            override suspend fun close() {}
        }
    }

    override suspend fun deleteParts(taskId: String) {
        parts.remove(taskId)
    }

    override suspend fun mergeParts(taskId: String, count: Int) {
        val map = parts[taskId] ?: return
        val first = map.getOrPut(0) { Part() }
        for (index in 1 until count) {
            val part = map.remove(index) ?: continue
            pushAll(first.chunks, part.chunks)
            first.size += part.size
        }
    }

    override suspend fun appendPart(fromTaskId: String, fromPart: Int, toTaskId: String, toPart: Int) {
        val source = parts[fromTaskId]?.remove(fromPart) ?: return
        val target = parts.getOrPut(toTaskId) { mutableMapOf() }.getOrPut(toPart) { Part() }
        pushAll(target.chunks, source.chunks)
        target.size += source.size
    }

    /** The browser names the saved file itself and renames on conflicts. */
    override suspend fun publish(task: DownloadTask, appFolder: String, conflict: ConflictPolicy): Published {
        val part = parts[task.id]?.get(0) ?: error("Nothing was downloaded")
        val url = blobUrl(part.chunks, MimeTypes.of(task.fileName, task.destination.type))
        saveAs(url, task.fileName)
        return Published(url)
    }

    override fun locationOf(destination: Destination, appFolder: String): String = ""
}

@JsFun("(target, source) => { for (const c of source) target.push(c); }")
private external fun pushAll(target: JsAny, source: JsAny)
