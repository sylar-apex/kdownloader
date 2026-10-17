package org.app.kdownloader.engine

/** Just enough of HLS: pick the best variant of a master playlist and list a media playlist's segments. */
internal object HlsPlaylist {
    /** A master playlist's choice: the highest-bandwidth variant and its separate audio rendition, if any. */
    data class Variant(val url: String, val audioUrl: String?)

    fun isMaster(text: String) = "#EXT-X-STREAM-INF" in text

    fun bestVariant(master: String, baseUrl: String): Variant {
        val lines = master.lines()
        val best = lines.withIndex().filter { it.value.startsWith("#EXT-X-STREAM-INF") }
            .maxByOrNull { BANDWIDTH.find(it.value)?.groupValues?.get(1)?.toLong() ?: 0 }
            ?: throw DownloadException("Empty HLS master playlist")
        val uri = lines.drop(best.index + 1).first { it.isNotBlank() && !it.startsWith("#") }
        val audio = AUDIO.find(best.value)?.groupValues?.get(1)?.let { group ->
            val media = lines.filter { it.startsWith("#EXT-X-MEDIA:") && "TYPE=AUDIO" in it && "GROUP-ID=\"$group\"" in it && "URI=" in it }
            val pick = media.firstOrNull { "DEFAULT=YES" in it } ?: media.firstOrNull()
            pick?.let { URI.find(it)?.groupValues?.get(1) }?.let { resolve(baseUrl, it) }
        }
        return Variant(resolve(baseUrl, uri), audio)
    }

    fun segments(media: String, baseUrl: String): List<String> =
        media.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { resolve(baseUrl, it) }

    /** [relative] against [base], like a browser does for absolute, scheme-relative, root and plain relative references. */
    fun resolve(base: String, relative: String): String {
        val ref = relative.trim()
        if ("://" in ref.substringBefore('?')) return ref
        val scheme = base.substringBefore("://")
        if (ref.startsWith("//")) return "$scheme:$ref"
        val afterScheme = base.substringAfter("://")
        val origin = "$scheme://" + afterScheme.substringBefore('/')
        if (ref.startsWith("/")) return origin + ref
        val path = base.substringBefore('?').substringBefore('#')
        val dir = if (path.indexOf('/', origin.length) >= 0) path.substringBeforeLast('/') else origin
        val segments = ArrayList<String>()
        dir.removePrefix(origin).split('/').filter { it.isNotEmpty() }.forEach { segments += it }
        val refPath = ref.substringBefore('?')
        val query = ref.substring(refPath.length)
        refPath.split('/').forEach { s ->
            when (s) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments += s
            }
        }
        val slash = if (refPath.endsWith("/")) "/" else ""
        return origin + "/" + segments.joinToString("/") + slash + query
    }

    private val BANDWIDTH = Regex("[^-]BANDWIDTH=(\\d+)")
    private val AUDIO = Regex("AUDIO=\"([^\"]+)\"")
    private val URI = Regex("URI=\"([^\"]+)\"")
}
