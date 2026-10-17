package org.app.kdownloader.engine

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.discard
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
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

    /** Size from a `Range: bytes=0-0` GET (the authoritative `Content-Range` total), or -1. */
    suspend fun probeRange(url: String, headers: Map<String, String>): Long = runCatching {
        client.prepareGet(url) {
            applyHeaders(headers)
            header(HttpHeaders.Range, "bytes=0-0")
        }.execute { response ->
            if (!response.status.isSuccess()) return@execute -1L
            totalFromContentRange(response.headers[HttpHeaders.ContentRange]) ?: response.contentLength() ?: -1L
        }
    }.getOrDefault(-1L)

    /** The body of [url] as text (playlists). */
    suspend fun getText(url: String, headers: Map<String, String>): String =
        client.prepareGet(url) { applyHeaders(headers) }.execute { response ->
            if (!response.status.isSuccess()) throw DownloadException("HTTP ${response.status.value}", response.status.value)
            response.bodyAsText()
        }

    /**
     * Writes bytes [start]..[end] of [url] into [writer]. Servers often close early, so it keeps
     * re-requesting from where it stopped; three requests in a row without progress fail with "Short read".
     * A 200 that ignores the range is skipped ahead to the offset.
     */
    suspend fun fetchRange(
        url: String,
        headers: Map<String, String>,
        start: Long,
        end: Long,
        writer: PartWriter,
        allowHtml: Boolean,
        onBytes: suspend (Int) -> Unit,
    ) {
        var pos = start
        var stalls = 0
        while (pos <= end) {
            val before = pos
            client.prepareGet(url) {
                applyHeaders(headers)
                header(HttpHeaders.Range, "bytes=$pos-$end")
            }.execute { response ->
                val code = response.status.value
                if (code != 206 && code != 200) throw DownloadException("HTTP $code", code)
                if (!allowHtml && response.isHtml()) throw DownloadException("Server returned a web page, not media", transient = true)
                val channel = response.bodyAsChannel()
                if (code == 200 && pos > 0) channel.discard(pos)
                val buffer = ByteArray(BUFFER_SIZE)
                try {
                    while (pos <= end) {
                        currentCoroutineContext().ensureActive()
                        val read = channel.readAvailable(buffer, 0, minOf(buffer.size.toLong(), end - pos + 1).toInt())
                        if (read < 0) break
                        if (read == 0) {
                            if (channel.isClosedForRead) break
                            continue
                        }
                        writer.write(buffer, 0, read)
                        pos += read
                        onBytes(read)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (pos == before) throw error
                }
            }
            if (pos == before && ++stalls >= 3) throw DownloadException("Short read", transient = true)
        }
    }

    /** Writes the whole body of [url] into [writer]. */
    suspend fun fetchWhole(url: String, headers: Map<String, String>, writer: PartWriter, allowHtml: Boolean, onBytes: suspend (Int) -> Unit) {
        client.prepareGet(url) { applyHeaders(headers) }.execute { response ->
            val code = response.status.value
            if (!response.status.isSuccess()) throw DownloadException("HTTP $code", code)
            if (!allowHtml && response.isHtml()) throw DownloadException("Server returned a web page, not media", transient = true)
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
        }
    }

    private fun HttpResponse.isHtml() = headers[HttpHeaders.ContentType].orEmpty().startsWith("text/html")

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
