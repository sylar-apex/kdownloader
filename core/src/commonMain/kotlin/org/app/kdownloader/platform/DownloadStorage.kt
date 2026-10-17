package org.app.kdownloader.platform

import org.app.kdownloader.model.ConflictPolicy
import org.app.kdownloader.model.Destination
import org.app.kdownloader.model.DownloadTask

/** Writes bytes into one part of a download. */
interface PartWriter {
    suspend fun write(bytes: ByteArray, offset: Int, length: Int)
    suspend fun close()
}

/**
 * Where partial downloads live while they run and where finished files go. Parts are numbered from 0;
 * a single-connection download uses only part 0.
 */
interface DownloadStorage {
    suspend fun partSize(taskId: String, part: Int): Long
    suspend fun openPart(taskId: String, part: Int, append: Boolean): PartWriter
    suspend fun deleteParts(taskId: String)

    /** Appends parts 1 until [count] onto part 0, in order, and deletes them. */
    suspend fun mergeParts(taskId: String, count: Int)

    /**
     * Moves part 0 to its public location, applying [conflict] when the name is taken, and returns a
     * uri the app can open or play.
     */
    suspend fun publish(task: DownloadTask, appFolder: String, conflict: ConflictPolicy): Published

    /** The folder a [destination] resolves to, for display; empty where there is none. */
    fun locationOf(destination: Destination, appFolder: String): String

    /** The local file of a part, or null where storage has no files. Its folder exists. */
    fun localPartPath(taskId: String, part: Int): String? = null

    /** Appends part [fromPart] of [fromTaskId] onto part [toPart] of [toTaskId] (creating it) and deletes the source. */
    suspend fun appendPart(fromTaskId: String, fromPart: Int, toTaskId: String, toPart: Int): Unit =
        throw UnsupportedOperationException("This storage cannot join parts")

    /** Copies a saved file ([uri]) into part [part] of [taskId]; false when unsupported. */
    suspend fun importPart(uri: String, taskId: String, part: Int): Boolean = false

    /** Deletes a saved file; false when it could not. */
    suspend fun deleteFile(uri: String): Boolean = false

    /** Renames a saved file; returns its new uri, or null when unsupported or it failed. */
    suspend fun renameFile(uri: String, newFileName: String): String? = null

    /**
     * Moves a finished file into app-private storage (galleries cannot see it) and returns its new uri.
     * Default: the file stays where it is (only the task's flag changes).
     */
    suspend fun hide(task: DownloadTask): String? = task.localUri

    /** Moves a hidden file back to its public location (applying [conflict]) and returns its new uri. */
    suspend fun unhide(task: DownloadTask, appFolder: String, conflict: ConflictPolicy): String? = task.localUri
}

/** The storage used by [org.app.kdownloader.KDownloader] when none is given. */
expect fun defaultStorage(): DownloadStorage

/** Where a finished file ended up. [skipped] is true when an existing file was kept instead. */
data class Published(val uri: String, val skipped: Boolean = false)

/** "clip.mp4" → "clip (2).mp4". */
fun numberedName(fileName: String, n: Int): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot <= 0) "$fileName ($n)" else "${fileName.substring(0, dot)} ($n)${fileName.substring(dot)}"
}
