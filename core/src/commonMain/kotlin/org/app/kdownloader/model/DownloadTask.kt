package org.app.kdownloader.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * One download and its progress. [localUri] is set once the file is saved. [segments] is how many
 * ranged parts the file is fetched in; 1 for a plain download. [order] keeps queue position among
 * equal priorities. [attempts] counts automatic retries so far. [speedBytesPerSec] is live and not stored.
 * [parts] is non-empty for multi-part downloads (see [DownloadPart]). [hidden] is true while the
 * finished file sits in app-private storage ([org.app.kdownloader.KDownloader.setHidden]).
 * [finishedAt] is the epoch millis the download completed, or 0.
 */
@Serializable
data class DownloadTask(
    val id: String,
    val url: String,
    val fileName: String,
    val destination: Destination,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val localUri: String? = null,
    val createdAt: Long = 0,
    val extras: Map<String, String> = emptyMap(),
    val segments: Int = 1,
    val headers: Map<String, String> = emptyMap(),
    val priority: Int = 0,
    val order: Long = 0,
    val tags: Set<String> = emptySet(),
    val connections: Int? = null,
    val wifiOnly: Boolean? = null,
    val speedLimitBytesPerSec: Long = 0,
    val notBefore: Long = 0,
    val attempts: Int = 0,
    @Transient val speedBytesPerSec: Long = 0,
    val parts: List<DownloadPart> = emptyList(),
    val hidden: Boolean = false,
    val finishedAt: Long = 0,
) {
    /** 0..1, or 0 while the size is unknown. */
    val progress: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    /** Estimated time left at the current speed, or -1 when unknown. */
    val etaMs: Long get() = if (speedBytesPerSec > 0 && totalBytes > 0) (totalBytes - downloadedBytes).coerceAtLeast(0) * 1000 / speedBytesPerSec else -1

    fun extra(key: String): String? = extras[key]
}
