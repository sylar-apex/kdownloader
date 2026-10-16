package org.app.kdownloader

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections

/** A local http server with Range support. `/slow/...` sends in small, delayed chunks. */
class TestServer(private val body: ByteArray) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val ranges: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val requestedPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val customHeaders: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val flakyHits = java.util.concurrent.atomic.AtomicInteger()

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            if (exchange.requestMethod == "GET") requestedPaths += path
            exchange.requestHeaders.getFirst("X-Test")?.let { customHeaders += it }
            if (path.startsWith("/flaky") && exchange.requestMethod == "GET" && flakyHits.incrementAndGet() <= FLAKY_FAILURES) {
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
                return@createContext
            }
            if (path.startsWith("/missing")) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            if (exchange.requestMethod == "HEAD") {
                exchange.responseHeaders.add("Content-Length", body.size.toString())
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
                return@createContext
            }
            val range = exchange.requestHeaders.getFirst("Range")
            var start = 0
            var end = body.size - 1
            if (range != null) {
                ranges += range
                val spec = range.removePrefix("bytes=")
                start = spec.substringBefore('-').toInt()
                spec.substringAfter('-').takeIf { it.isNotEmpty() }?.let { end = it.toInt() }
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
            }
            val length = end - start + 1
            exchange.sendResponseHeaders(if (range != null) 206 else 200, length.toLong())
            runCatching {
                exchange.responseBody.use { out ->
                    if (path.startsWith("/slow")) {
                        var offset = start
                        while (offset <= end) {
                            val n = minOf(SLOW_CHUNK, end - offset + 1)
                            out.write(body, offset, n)
                            out.flush()
                            offset += n
                            Thread.sleep(SLOW_DELAY_MS)
                        }
                    } else {
                        out.write(body, start, length)
                    }
                }
            }
            exchange.close()
        }
        server.start()
    }

    fun url(path: String) = "http://127.0.0.1:${server.address.port}/$path"

    override fun close() = server.stop(0)

    private companion object {
        const val SLOW_CHUNK = 4 * 1024
        const val SLOW_DELAY_MS = 20L
        const val FLAKY_FAILURES = 2
    }
}
