package org.app.kdownloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.app.kdownloader.KDownloaderAndroid
import org.app.kdownloader.model.DownloadStatus
import org.app.kdownloader.model.DownloadTask
import org.app.kdownloader.model.title

/**
 * Texts, icons and the tap action of the download notifications. Every text is supplied by the app
 * (defaults are English).
 *
 * - [title]: ongoing title for the number of active downloads (0 while preparing).
 * - [text]: ongoing text for bytes done and total (total is 0 while unknown).
 * - [completeTitle], [completeText]: the per-task completion notification, shown when [notifyComplete].
 * - [contentIntent]: what tapping a notification opens, e.g. the app's downloads screen.
 */
data class NotificationConfig(
    val channelId: String = "downloads",
    val channelName: String = "Downloads",
    val ongoingId: Int = 4100,
    val smallIcon: Int = android.R.drawable.stat_sys_download,
    val completeIcon: Int = android.R.drawable.stat_sys_download_done,
    val title: (count: Int) -> String = { count -> if (count > 0) "Downloading $count file${if (count > 1) "s" else ""}" else "Preparing download" },
    val text: (done: Long, total: Long) -> String = { done, total -> if (total > 0) "${formatSize(done)} / ${formatSize(total)}" else "" },
    val notifyComplete: Boolean = true,
    val completeTitle: String = "Download complete",
    val completeText: (DownloadTask) -> String = { it.title },
    val contentIntent: ((Context) -> PendingIntent?)? = null,
)

/** "1.5 GB", "12.3 MB", "40 KB", "12 B". */
fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Keeps the process alive while downloads run and shows overall progress (active count, bytes done
 * of total). Started by [KDownloaderAndroid.enableForegroundService]; stops itself when the queue is idle.
 */
class DownloadService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (downloader, config) = KDownloaderAndroid.foreground ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        channel(this, config)
        ServiceCompat.startForeground(this, config.ongoingId, build(config, 0, 0, 0), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (job == null) job = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                val active = downloader.tasks.value.filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.MERGING }
                if (active.isEmpty()) break
                val total = active.sumOf { maxOf(it.totalBytes, 0) }
                val done = active.sumOf { it.downloadedBytes }
                getSystemService(NotificationManager::class.java).notify(config.ongoingId, build(config, active.size, done, total))
                delay(1000)
            }
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            job = null
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun build(config: NotificationConfig, count: Int, done: Long, total: Long): Notification =
        NotificationCompat.Builder(this, config.channelId)
            .setSmallIcon(config.smallIcon)
            .setContentTitle(config.title(count))
            .setContentText(config.text(done, total))
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
            .setOngoing(true)
            .setContentIntent(config.contentIntent?.invoke(this))
            .build()

    internal companion object {
        fun start(context: Context) = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }

        fun channel(context: Context, config: NotificationConfig) {
            if (Build.VERSION.SDK_INT >= 26) {
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(NotificationChannel(config.channelId, config.channelName, NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun notifyComplete(context: Context, config: NotificationConfig, task: DownloadTask) {
            channel(context, config)
            runCatching {
                context.getSystemService(NotificationManager::class.java).notify(
                    task.id.hashCode(),
                    NotificationCompat.Builder(context, config.channelId)
                        .setSmallIcon(config.completeIcon)
                        .setContentTitle(config.completeTitle)
                        .setContentText(config.completeText(task))
                        .setAutoCancel(true)
                        .setContentIntent(config.contentIntent?.invoke(context))
                        .build(),
                )
            }
        }
    }
}
