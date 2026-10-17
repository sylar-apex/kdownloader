package org.app.kdownloader.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class HlsPlaylistTest {
    private val base = "https://cdn.example.com/v/master.m3u8?sig=1"

    @Test
    fun resolvesReferences() {
        assertEquals("https://cdn.example.com/v/a/b.ts", HlsPlaylist.resolve(base, "a/b.ts"))
        assertEquals("https://cdn.example.com/x.ts?t=2", HlsPlaylist.resolve(base, "/x.ts?t=2"))
        assertEquals("https://cdn.example.com/up.ts", HlsPlaylist.resolve(base, "../up.ts"))
        assertEquals("https://other.com/y.ts", HlsPlaylist.resolve(base, "//other.com/y.ts"))
        assertEquals("http://z.com/z.ts", HlsPlaylist.resolve(base, "http://z.com/z.ts"))
    }

    @Test
    fun picksTheHighestBandwidthAndItsDefaultAudio() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="en",DEFAULT=NO,URI="audio/en2.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="en",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=500,AUDIO="aud"
            low.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=9,BANDWIDTH=2000,AUDIO="aud"
            high.m3u8
        """.trimIndent()
        val variant = HlsPlaylist.bestVariant(master, base)
        assertEquals("https://cdn.example.com/v/high.m3u8", variant.url)
        assertEquals("https://cdn.example.com/v/audio/en.m3u8", variant.audioUrl)
    }

    @Test
    fun listsSegments() {
        assertEquals(
            listOf("https://cdn.example.com/v/1.ts", "https://cdn.example.com/v/2.ts"),
            HlsPlaylist.segments("#EXTM3U\n#EXTINF:2,\n1.ts\n\n#EXTINF:2,\n2.ts\n#EXT-X-ENDLIST", base),
        )
    }
}
