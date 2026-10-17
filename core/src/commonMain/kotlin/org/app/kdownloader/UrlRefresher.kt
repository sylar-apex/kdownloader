package org.app.kdownloader

import org.app.kdownloader.model.DownloadPart
import org.app.kdownloader.model.DownloadTask

/**
 * Gives fresh urls for a multi-part task whose signed urls expired. Called when a part answers 403
 * or 410 (once per part run), and before a task the user resumed starts again.
 *
 * Return the parts with new [DownloadPart.url] (and optionally [DownloadPart.headers], merged over the
 * old ones), matched to the task's parts by [DownloadPart.key] when set, otherwise by position; or null
 * to keep the old urls. Download progress is kept.
 */
fun interface UrlRefresher {
    suspend fun refresh(task: DownloadTask): List<DownloadPart>?
}
