package org.app.kdownloader.merge

/** No built-in muxer here: see [FallbackMerger]. */
actual fun defaultMerger(): Merger = FallbackMerger()

/** Unsupported here: [org.app.kdownloader.KDownloader.convertToMusic] returns null. */
actual fun defaultAudioExtractor(): AudioExtractor? = null
