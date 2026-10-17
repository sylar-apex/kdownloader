package org.app.kdownloader.merge

import org.app.kdownloader.model.DownloadTask
import org.app.kdownloader.platform.DownloadStorage

/** One downloaded stream handed to a [Merger]. [path] is its local file, or null where storage has no files (web). */
class MergeInput internal constructor(
    /** Index of the [org.app.kdownloader.model.DownloadPart] it came from. */
    val partIndex: Int,
    /** Came from an HLS playlist: MPEG-TS (or fMP4) segments joined end to end. */
    val hls: Boolean,
    val path: String?,
    internal val storageKey: String,
)

/**
 * The work of one merge: turn [inputs] into the task's final file at [outputPath]. A [Merger] either
 * writes [outputPath] itself (muxers need real files) or calls [keepFirst] / [concatenate].
 */
class MergeJob internal constructor(
    val task: DownloadTask,
    val inputs: List<MergeInput>,
    private val storage: DownloadStorage,
) {
    /** Where the final file must be written, or null where storage has no files. */
    val outputPath: String? = storage.localPartPath(task.id, 0)

    /** Uses the first input as the final file and drops the rest. */
    suspend fun keepFirst() {
        storage.deleteParts(task.id)
        storage.appendPart(inputs.first().storageKey, 0, task.id, 0)
    }

    /** Appends every input byte for byte, in order. Only meaningful for formats that allow it (MPEG-TS, split files). */
    suspend fun concatenate() {
        storage.deleteParts(task.id)
        inputs.forEach { storage.appendPart(it.storageKey, 0, task.id, 0) }
    }
}

/**
 * Produces the final file of a multi-part download (status [org.app.kdownloader.model.DownloadStatus.MERGING]),
 * e.g. muxes a video stream and an audio stream into one MP4. Called when a task has more than one
 * stream or any HLS stream. Throwing fails the task.
 */
fun interface Merger {
    suspend fun merge(job: MergeJob)
}

/**
 * The merger for platforms without a built-in muxer (desktop, iOS, web). It cannot mux video and
 * audio: by default it keeps the first stream only (the video of a video + audio pair; an HLS
 * stream as raw MPEG-TS). With [concatenate] = true it appends all streams byte for byte, which is
 * only correct for concatenable formats. Supply your own [Merger] (e.g. one calling ffmpeg) for real muxing.
 */
class FallbackMerger(private val concatenate: Boolean = false) : Merger {
    override suspend fun merge(job: MergeJob) = if (concatenate) job.concatenate() else job.keepFirst()
}

/** The platform's merger: MediaMuxer on Android, [FallbackMerger] elsewhere. */
expect fun defaultMerger(): Merger

/** Copies the audio track of a media file into a new file without re-encoding. */
fun interface AudioExtractor {
    /** Reads [inputPath], writes an M4A to [outputPath]; throws when there is no audio track. */
    suspend fun extractAudio(inputPath: String, outputPath: String)
}

/** MediaExtractor + MediaMuxer on Android; null (unsupported) elsewhere. */
expect fun defaultAudioExtractor(): AudioExtractor?
