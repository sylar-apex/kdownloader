package org.app.kdownloader.model

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStatus {
    QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED,

    /** Every part is downloaded; the [org.app.kdownloader.merge.Merger] is producing the final file. */
    MERGING;

    /** Still in the queue: waiting, downloading, merging or paused. */
    val isActive: Boolean get() = this == QUEUED || this == RUNNING || this == PAUSED || this == MERGING

    /** Holds a download slot: downloading or merging. */
    val isWorking: Boolean get() = this == RUNNING || this == MERGING

    /** Can be started again with resume. */
    val isResumable: Boolean get() = this == PAUSED || this == FAILED
}
