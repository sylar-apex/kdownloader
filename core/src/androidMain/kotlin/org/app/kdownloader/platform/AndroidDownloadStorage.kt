package org.app.kdownloader.platform

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import org.app.kdownloader.model.ConflictPolicy
import org.app.kdownloader.model.Destination
import org.app.kdownloader.model.DownloadTask
import org.app.kdownloader.model.Folder
import org.app.kdownloader.model.MediaType

/**
 * Parts live in the app cache. Finished files are published through MediaStore on Android 10+ (no
 * storage permission needed) and copied into the public folders on older versions.
 */
class AndroidDownloadStorage(private val context: Context) : FileDownloadStorage(Path(File(context.cacheDir, PARTS_DIR).path)) {

    override suspend fun publish(task: DownloadTask, appFolder: String, conflict: ConflictPolicy): Published = withContext(ioDispatcher) {
        val part = File(partPath(task.id, 0).toString())
        val destination = task.destination
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val collection = when {
                destination.folder == Folder.DOWNLOADS || destination.type == MediaType.OTHER -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                destination.type == MediaType.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val relative = relativeDir(destination, appFolder)
            val existing = findExisting(collection, relative, task.fileName)
            if (existing != null) {
                when (conflict) {
                    ConflictPolicy.SKIP -> {
                        part.delete()
                        return@withContext Published(existing.toString(), skipped = true)
                    }
                    ConflictPolicy.OVERWRITE -> resolver.delete(existing, null, null)
                    ConflictPolicy.RENAME -> Unit
                }
            }
            val values = ContentValues().apply {
                // MediaStore appends " (1)" by itself when the name is taken, which covers RENAME.
                put(MediaStore.MediaColumns.DISPLAY_NAME, task.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, MimeTypes.of(task.fileName, destination.type))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("Could not save the download")
            resolver.openOutputStream(uri)?.use { output -> part.inputStream().use { it.copyTo(output) } }
                ?: error("Could not write the download")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            part.delete()
            return@withContext Published(uri.toString())
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStorageDirectory(), relativeDir(destination, appFolder)).apply { mkdirs() }
        var target = File(dir, task.fileName)
        if (target.exists()) {
            when (conflict) {
                ConflictPolicy.SKIP -> {
                    part.delete()
                    return@withContext Published(Uri.fromFile(target).toString(), skipped = true)
                }
                ConflictPolicy.OVERWRITE -> Unit
                ConflictPolicy.RENAME -> target = generateSequence(2) { it + 1 }.map { File(dir, numberedName(task.fileName, it)) }.first { !it.exists() }
            }
        }
        part.copyTo(target, overwrite = true)
        part.delete()
        Published(Uri.fromFile(target).toString())
    }

    /** The MediaStore row already holding [fileName] in [relative], if any. */
    private fun findExisting(collection: Uri, relative: String, fileName: String): Uri? {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        return context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, arrayOf("$relative/", fileName), null)
            ?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }
    }

    @Suppress("DEPRECATION")
    override fun locationOf(destination: Destination, appFolder: String): String =
        Environment.getExternalStorageDirectory().path + "/" + relativeDir(destination, appFolder) + "/"

    private fun relativeDir(destination: Destination, appFolder: String): String = when {
        destination.folder == Folder.DOWNLOADS || destination.type == MediaType.OTHER -> "${Environment.DIRECTORY_DOWNLOADS}/$appFolder"
        destination.type == MediaType.AUDIO -> "${Environment.DIRECTORY_MUSIC}/$appFolder"
        else -> "${Environment.DIRECTORY_MOVIES}/$appFolder"
    }

    private companion object {
        const val PARTS_DIR = "kdownloader"
    }
}
