package org.app.kdownloader.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.io.files.Path
import org.app.kdownloader.model.ConflictPolicy
import org.app.kdownloader.model.Destination
import org.app.kdownloader.model.DownloadTask
import org.app.kdownloader.model.Folder
import org.app.kdownloader.model.MediaType

internal actual fun defaultHttpClient(): HttpClient = HttpClient(CIO) { followRedirects = true }

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun defaultNetworkMonitor(): NetworkMonitor = NetworkMonitor { true }

actual fun defaultStorage(): DownloadStorage = DesktopDownloadStorage()

/**
 * Parts in the temp folder; finished files in ~/Movies, ~/Music or ~/Downloads under the app folder;
 * hidden files in [privateDir].
 */
class DesktopDownloadStorage(
    partsDir: File = File(System.getProperty("java.io.tmpdir"), "kdownloader"),
    private val home: File = File(System.getProperty("user.home")),
    privateDir: File = File(home, ".kdownloader/private"),
) : FileDownloadStorage(Path(partsDir.path), Path(privateDir.path)) {

    override fun pathOf(uri: String): Path? =
        if (uri.startsWith("file:")) runCatching { Path(File(java.net.URI(uri)).path) }.getOrNull() else super.pathOf(uri)

    override fun uriOf(path: Path): String = File(path.toString()).toURI().toString()

    override suspend fun publish(task: DownloadTask, appFolder: String, conflict: ConflictPolicy): Published {
        val (target, skipped) = resolveTarget(Path(folder(task.destination, appFolder).path), task.fileName, conflict)
        if (!skipped) movePartTo(task, target)
        return Published(File(target.toString()).toURI().toString(), skipped)
    }

    override fun locationOf(destination: Destination, appFolder: String): String = folder(destination, appFolder).path + File.separator

    private fun folder(destination: Destination, appFolder: String): File {
        val root = when {
            destination.folder == Folder.DOWNLOADS || destination.type == MediaType.OTHER -> "Downloads"
            destination.type == MediaType.AUDIO -> "Music"
            else -> "Movies"
        }
        return File(File(home, root), appFolder)
    }
}
