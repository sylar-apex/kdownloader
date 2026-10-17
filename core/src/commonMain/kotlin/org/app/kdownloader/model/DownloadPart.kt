package org.app.kdownloader.model

import kotlinx.serialization.Serializable

/**
 * One resource of a multi-part download, e.g. the video or the audio stream of a clip. Each part is
 * downloaded to its own file; when all are done a [org.app.kdownloader.merge.Merger] joins them.
 *
 * - [url], [headers]: where to get it. Headers are sent as given; a `Cookie` header is dropped on
 *   retries, because CDN bot walls reject browser cookies replayed from another client.
 * - [size]: expected size, or -1. The server's `Content-Range` replaces it when they differ.
 * - [allowHtml]: an HTML response is the content (a saved web page), not an error page.
 * - [hls]: [url] is an HLS playlist (master or media); its segments are downloaded and joined.
 * - [key]: the app's id for this stream (e.g. an itag), used to match parts returned by a
 *   [org.app.kdownloader.UrlRefresher].
 * - [doneSegments]: progress, kept by the library: finished fixed-size segments (plain parts) or
 *   finished playlist segments (HLS), so a resumed download skips them.
 */
@Serializable
data class DownloadPart(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val size: Long = -1,
    val allowHtml: Boolean = false,
    val hls: Boolean = false,
    val key: String = "",
    val doneSegments: Set<Int> = emptySet(),
)
