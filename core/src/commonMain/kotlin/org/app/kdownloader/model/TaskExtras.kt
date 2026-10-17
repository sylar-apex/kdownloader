package org.app.kdownloader.model

/**
 * Well-known [DownloadTask.extras] keys for media downloads. The library only carries them (and
 * updates [TITLE] on [org.app.kdownloader.KDownloader.rename]); their meaning is the app's.
 */
object TaskExtras {
    const val TITLE = "title"
    const val EXT = "ext"
    const val KIND = "kind"
    const val QUALITY = "quality"
    const val THUMBNAIL = "thumbnail"
    const val DURATION = "duration"
    const val PAGE_URL = "pageUrl"
    const val VIDEO_ID = "videoId"
    const val SUBTITLE_URL = "subtitleUrl"
}

/** [TaskExtras.TITLE], or the file name without its extension. */
val DownloadTask.title: String get() = extras[TaskExtras.TITLE] ?: fileName.substringBeforeLast('.')

/** [TaskExtras.EXT], or the file name's extension. */
val DownloadTask.ext: String get() = extras[TaskExtras.EXT] ?: fileName.substringAfterLast('.', "")

val DownloadTask.kind: String? get() = extras[TaskExtras.KIND]
val DownloadTask.quality: String get() = extras[TaskExtras.QUALITY].orEmpty()
val DownloadTask.thumbnail: String get() = extras[TaskExtras.THUMBNAIL].orEmpty()
val DownloadTask.duration: String get() = extras[TaskExtras.DURATION].orEmpty()
val DownloadTask.pageUrl: String get() = extras[TaskExtras.PAGE_URL].orEmpty()
val DownloadTask.videoId: String? get() = extras[TaskExtras.VIDEO_ID]
val DownloadTask.subtitleUrl: String? get() = extras[TaskExtras.SUBTITLE_URL]
