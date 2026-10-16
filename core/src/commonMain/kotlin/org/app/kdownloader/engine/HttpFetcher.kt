package org.app.kdownloader.engine

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.app.kdownloader.platform.PartWriter

/** What a HEAD request tells about a file. [totalBytes] is -1 when unknown. */
internal data class Probe(val totalBytes: Long, val acceptsRanges: Boolean)

/** Streams http responses into [PartWriter]s, resuming with Range requests. */
internal class HttpFetcher(private val client: HttpClient, private val userAgent: String) {

    suspend fun probe(url: String, headers: Map<String, String>): Probe = runCatching {
        val response = client.head(url) { applyHeaders(headers) }
        if (!response.status.isSuccess()) return Probe(-1, false)
        val ranges = response.headers[HttpHeaders.AcceptRanges]?.contains("bytes") == true
        Probe(response.contentLength() ?: -1, ranges)
    }.getOrDefault(Probe(-1, false))

    /**
     * Downloads [url] from byte [from] up to [to] (inclusive, null for the end). [open] gives the writer:
     * append = true continues the existing part, false starts it over when the server ignored the range.
     * [onTotal] receives the full size as soon as the headers arrive. Returns it too, or -1 when the server did not say.
     */
    suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        from: Long,
        to: Long?,
        open: suspend (append: Boolean) -> PartWriter,
        onBytes: suspend (Int) -> Unit,
        onTotal: (Long) -> Unit = {},
    ): Long {
        val ranged = from > 0 || to != null
        return client.prepareGet(url) {
            applyHeaders(headers)
            if (ranged) header(HttpHeaders.Range, "bytes=$from-${to ?: ""}")
        }.execute { response ->
            val status = response.status
            if (!status.isSuccess()) throw DownloadException("HTTP ${status.value}", status.value)
            val partial = status == HttpStatusCode.PartialContent
            if (to != null && !partial) throw DownloadException("Range request was not accepted")
            val length = response.contentLength() ?: -1
            val total = when {
                partial -> totalFromContentRange(response.headers[HttpHeaders.ContentRange]) ?: if (length >= 0) from + length else -1
                else -> length
            }
            if (total > 0) onTotal(total)
            val writer = open(partial && from > 0)
            try {
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) {
                        if (channel.isClosedForRead) break
                        continue
                    }
                    writer.write(buffer, 0, read)
                    onBytes(read)
                }
            } finally {
                writer.close()
            }
            total
        }
    }

    /** The user agent first, so a request header can replace it. */
    private fun HttpRequestBuilder.applyHeaders(headers: Map<String, String>) {
        header(HttpHeaders.UserAgent, userAgent)
        headers.forEach { (name, value) ->
            this.headers.remove(name)
            header(name, value)
        }
    }

    private fun totalFromContentRange(header: String?): Long? =
        header?.substringAfterLast('/', "")?.toLongOrNull()

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
