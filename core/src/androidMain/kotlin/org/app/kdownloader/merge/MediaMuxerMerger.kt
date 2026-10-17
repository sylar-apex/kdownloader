package org.app.kdownloader.merge

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun defaultMerger(): Merger = MediaMuxerMerger()

actual fun defaultAudioExtractor(): AudioExtractor? = MediaAudioExtractor()

/**
 * Joins streams into one MP4 with MediaMuxer, without re-encoding: separate video + audio streams are
 * muxed track by track; HLS (MPEG-TS) streams are remuxed with every track, keeping timestamps rising
 * across discontinuities, and fall back to the raw TS of the first stream when remuxing fails.
 */
class MediaMuxerMerger : Merger {
    override suspend fun merge(job: MergeJob) = withContext(Dispatchers.IO) {
        val output = job.outputPath ?: error("No output file")
        val inputs = job.inputs.map { File(it.path ?: error("No input file")) }
        if (job.inputs.any { it.hls }) {
            runCatching { remux(inputs, File(output)) }.onFailure {
                File(output).delete()
                job.keepFirst()
            }
        } else {
            mux(inputs, File(output))
        }
        Unit
    }

    companion object {
        /** Track 0 of every input into one MP4 (video + audio streams). */
        fun mux(inputs: List<File>, out: File) {
            val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val extractors = inputs.map { f -> MediaExtractor().apply { setDataSource(f.path) } }
            try {
                val map = extractors.map { ex ->
                    ex.selectTrack(0)
                    muxer.addTrack(ex.getTrackFormat(0))
                }
                muxer.start()
                val buf = ByteBuffer.allocate(4 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                extractors.forEachIndexed { i, ex ->
                    while (true) {
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        info.set(0, size, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(map[i], buf, info)
                        ex.advance()
                    }
                }
                muxer.stop()
            } finally {
                extractors.forEach { it.release() }
                runCatching { muxer.release() }
            }
        }

        /** TS (or any) files into one MP4, every track; keeps timestamps rising across HLS discontinuities. */
        fun remux(inputs: List<File>, out: File) {
            val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val extractors = inputs.map { f -> MediaExtractor().apply { setDataSource(f.path) } }
            try {
                val tracks = extractors.flatMap { ex ->
                    (0 until ex.trackCount).mapNotNull { t ->
                        val fmt = ex.getTrackFormat(t)
                        val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                            ex.selectTrack(t)
                            Triple(ex, t, muxer.addTrack(fmt))
                        } else {
                            null
                        }
                    }
                }
                require(tracks.isNotEmpty())
                muxer.start()
                val buf = ByteBuffer.allocate(4 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                val offset = HashMap<Int, Long>()
                val last = HashMap<Int, Long>()
                extractors.forEach { ex ->
                    while (true) {
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        val track = tracks.first { it.first === ex && it.second == ex.sampleTrackIndex }.third
                        var t = ex.sampleTime + (offset[track] ?: 0)
                        val prev = last[track]
                        // A jump back of over a second is a discontinuity (new clip); small dips are just B-frame reordering.
                        if (prev != null && t < prev - 1_000_000) {
                            offset[track] = (offset[track] ?: 0) + prev - t + 33_000
                            t = prev + 33_000
                        }
                        last[track] = maxOf(t, prev ?: t)
                        info.set(0, size, t, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(track, buf, info)
                        ex.advance()
                    }
                }
                muxer.stop()
            } finally {
                extractors.forEach { it.release() }
                runCatching { muxer.release() }
            }
        }
    }
}

/** Copies the first audio track into an M4A with MediaExtractor + MediaMuxer (no re-encode). */
class MediaAudioExtractor : AudioExtractor {
    override suspend fun extractAudio(inputPath: String, outputPath: String) = withContext(Dispatchers.IO) {
        val ex = MediaExtractor().apply { setDataSource(inputPath) }
        var muxer: MediaMuxer? = null
        try {
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            ex.selectTrack(track)
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dst = muxer.addTrack(ex.getTrackFormat(track))
            muxer.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, 0)
                muxer.writeSampleData(dst, buf, info)
                ex.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer?.release() }
            ex.release()
        }
    }
}
