package org.app.kdownloader.engine

/** A download that cannot continue. [httpStatus] is set for http errors; [transient] marks other errors worth retrying. */
class DownloadException(message: String, val httpStatus: Int? = null, private val transient: Boolean = false) : Exception(message) {
    /** Server errors, timeouts and rate limits are worth retrying; other 4xx are not. */
    val retryable: Boolean get() = if (httpStatus != null) httpStatus >= 500 || httpStatus == 408 || httpStatus == 429 else transient
}

/** Whether an exception from a download should trigger an automatic retry. */
internal fun Throwable.isRetryable(): Boolean = when (this) {
    is DownloadException -> retryable
    else -> true
}
