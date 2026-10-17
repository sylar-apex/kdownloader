package org.app.kdownloader

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.app.kdownloader.model.DownloadEvent
import org.app.kdownloader.model.DownloadStatus
import org.app.kdownloader.service.DownloadService
import org.app.kdownloader.service.NotificationConfig

/** Call [init] once from `Application.onCreate` before creating a [KDownloader]. */
object KDownloaderAndroid {
    lateinit var context: Context
        private set

    internal var foreground: Pair<KDownloader, NotificationConfig>? = null
        private set

    private var watcher: CoroutineScope? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * Runs [downloader] in a foreground service while anything is queued, running or merging, with an
     * ongoing notification of the active count and bytes, and (when [NotificationConfig.notifyComplete])
     * a notification per finished download. Needs [init] first; on Android 13+ the app asks for the
     * POST_NOTIFICATIONS permission itself. Calling it again replaces the previous downloader and config.
     */
    fun enableForegroundService(downloader: KDownloader, config: NotificationConfig = NotificationConfig()) {
        disableForegroundService()
        foreground = downloader to config
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { watcher = it }
        scope.launch {
            downloader.tasks
                .map { list -> list.any { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.MERGING } }
                .distinctUntilChanged()
                .collect { busy -> if (busy) DownloadService.start(context) }
        }
        scope.launch {
            downloader.events.filterIsInstance<DownloadEvent.Completed>().collect { event ->
                if (config.notifyComplete) DownloadService.notifyComplete(context, config, event.task)
            }
        }
    }

    /** Stops watching; a running service ends by itself once the queue is idle. */
    fun disableForegroundService() {
        watcher?.cancel()
        watcher = null
        foreground = null
    }
}
