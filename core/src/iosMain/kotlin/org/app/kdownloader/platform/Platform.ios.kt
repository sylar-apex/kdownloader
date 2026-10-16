package org.app.kdownloader.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.io.files.Path
import org.app.kdownloader.model.ConflictPolicy
import org.app.kdownloader.model.Destination
import org.app.kdownloader.model.DownloadTask
import org.app.kdownloader.model.Folder
import org.app.kdownloader.model.MediaType
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

internal actual fun defaultHttpClient(): HttpClient = HttpClient(Darwin)

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun defaultNetworkMonitor(): NetworkMonitor = NetworkMonitor { true }

actual fun defaultStorage(): DownloadStorage = IosDownloadStorage()

/** Parts in the temp folder; finished files under Documents, visible in the Files app. */
class IosDownloadStorage : FileDownloadStorage(Path(NSTemporaryDirectory(), "kdownloader")) {
    private val documents: String =
        (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String).orEmpty()

    override suspend fun publish(task: DownloadTask, appFolder: String, conflict: ConflictPolicy): Published {
        val (target, skipped) = resolveTarget(Path(folder(task.destination, appFolder)), task.fileName, conflict)
        if (!skipped) movePartTo(task, target)
        return Published("file://$target", skipped)
    }

    override fun locationOf(destination: Destination, appFolder: String): String = folder(destination, appFolder) + "/"

    private fun folder(destination: Destination, appFolder: String): String {
        val root = when {
            destination.folder == Folder.DOWNLOADS || destination.type == MediaType.OTHER -> "Downloads"
            destination.type == MediaType.AUDIO -> "Music"
            else -> "Movies"
        }
        return "$documents/$root/$appFolder"
    }
}
