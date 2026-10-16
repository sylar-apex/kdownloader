package org.app.kdownloader.model

/** One-off happenings for the app to react to (notifications, toasts). */
sealed interface DownloadEvent {
    data class Started(val task: DownloadTask) : DownloadEvent
    data class Completed(val task: DownloadTask) : DownloadEvent
    data class Failed(val task: DownloadTask) : DownloadEvent

    /** [task] failed and will be tried again in [delayMs]; [attempt] counts from 1. */
    data class Retrying(val task: DownloadTask, val attempt: Int, val delayMs: Long) : DownloadEvent

    /** The file already existed and [ConflictPolicy.SKIP] kept it. */
    data class Skipped(val task: DownloadTask) : DownloadEvent

    /** Queued downloads are waiting because Wi-Fi only is on and the network is metered. */
    data object WaitingForWifi : DownloadEvent
}

/** The answer to [org.app.kdownloader.KDownloader.enqueue]. */
sealed interface EnqueueResult {
    data class Added(val task: DownloadTask) : EnqueueResult

    /** The same url is already queued, running or paused. */
    data class Duplicate(val existing: DownloadTask) : EnqueueResult
}
