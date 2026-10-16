package org.app.kdownloader.model

/**
 * Queue behaviour, shared by every task unless a [DownloadRequest] overrides it.
 *
 * - [maxParallel]: how many files download at the same time (1..[MAX_PARALLEL]).
 * - [connections]: ranged connections per file (1..[MAX_CONNECTIONS]); used only when the server
 *   supports ranges and each part would be at least [minSegmentBytes].
 * - [speedLimitBytesPerSec]: cap on the total speed of all downloads; 0 means unlimited.
 * - [retry]: automatic retries of network errors, with exponential backoff.
 * - [conflict]: what to do when a file with the same name already exists.
 * - [autoResume]: restart interrupted downloads on the next launch; otherwise they come back paused.
 * - [networkRecheckMs]: how often a Wi-Fi-only queue checks whether Wi-Fi is back.
 * - [appFolder]: sub-folder inside Movies, Music or Download.
 */
data class DownloaderConfig(
    val maxParallel: Int = 2,
    val wifiOnly: Boolean = false,
    val connections: Int = 1,
    val minSegmentBytes: Long = 512_000,
    val speedLimitBytesPerSec: Long = 0,
    val retry: RetryPolicy = RetryPolicy(),
    val conflict: ConflictPolicy = ConflictPolicy.RENAME,
    val autoResume: Boolean = true,
    val progressIntervalMs: Long = 150,
    val networkRecheckMs: Long = 5_000,
    val appFolder: String = "KDownloader",
    val userAgent: String = "KDownloader/1.0",
) {
    val parallel: Int get() = maxParallel.coerceIn(1, MAX_PARALLEL)

    companion object {
        const val MAX_PARALLEL = 16
        const val MAX_CONNECTIONS = 16
    }
}

/** Retries network failures and 5xx/408/429 responses after [initialDelayMs], growing by [factor] up to [maxDelayMs]. */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 2_000,
    val maxDelayMs: Long = 30_000,
    val factor: Double = 2.0,
) {
    /** The wait before retry number [attempt] (1-based). */
    fun delayFor(attempt: Int): Long {
        var delay = initialDelayMs.toDouble()
        repeat(attempt - 1) { delay *= factor }
        return delay.toLong().coerceAtMost(maxDelayMs)
    }

    companion object {
        val None = RetryPolicy(maxAttempts = 0)
    }
}

/** When the target file name is taken: save as "name (1).ext", replace the old file, or keep it and skip the download. */
enum class ConflictPolicy { RENAME, OVERWRITE, SKIP }
