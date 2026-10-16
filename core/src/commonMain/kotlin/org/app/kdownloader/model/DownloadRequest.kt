package org.app.kdownloader.model

/**
 * A file to download.
 *
 * - [headers]: extra request headers (cookies, referer, auth) sent with every request of this file.
 * - [priority]: higher starts first; equal priorities keep queue order.
 * - [tags]: labels for group actions ([org.app.kdownloader.KDownloader.pauseTag] and friends).
 * - [connections], [wifiOnly]: per-file overrides of the [DownloaderConfig] values.
 * - [speedLimitBytesPerSec]: cap for this file alone; 0 means only the global limit applies.
 * - [notBefore]: epoch millis before which the download does not start.
 * - [extras]: the app's own data, carried untouched.
 * - [totalBytesHint]: size shown until the server reports the real one.
 */
data class DownloadRequest(
    val url: String,
    val fileName: String,
    val destination: Destination = Destination(),
    val headers: Map<String, String> = emptyMap(),
    val priority: Int = 0,
    val tags: Set<String> = emptySet(),
    val connections: Int? = null,
    val wifiOnly: Boolean? = null,
    val speedLimitBytesPerSec: Long = 0,
    val notBefore: Long = 0,
    val totalBytesHint: Long = 0,
    val extras: Map<String, String> = emptyMap(),
)
