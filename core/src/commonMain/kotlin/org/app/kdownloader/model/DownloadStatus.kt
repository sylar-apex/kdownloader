package org.app.kdownloader.model

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStatus {
    QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED;

    /** Still in the queue: waiting, downloading or paused. */
    val isActive: Boolean get() = this == QUEUED || this == RUNNING || this == PAUSED

    /** Can be started again with resume. */
    val isResumable: Boolean get() = this == PAUSED || this == FAILED
}
