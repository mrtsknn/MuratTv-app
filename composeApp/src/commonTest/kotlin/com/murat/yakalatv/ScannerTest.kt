package com.murat.yakalatv

import kotlin.test.Test
import kotlin.test.assertEquals

class ScannerTest {
    @Test
    fun picksHighestVariant() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            hi/index.m3u8
        """.trimIndent()
        assertEquals(1080 to "hi/index.m3u8", Scanner.variants(master).maxBy { it.first })
    }

    @Test
    fun resolvesRelativeUrls() {
        val base = "http://a.tv:8080/live/ch/index.m3u8?token=1"
        assertEquals("http://a.tv:8080/live/ch/seg1.ts", Scanner.resolve(base, "seg1.ts"))
        assertEquals("http://a.tv:8080/hls/seg1.ts", Scanner.resolve(base, "/hls/seg1.ts"))
        assertEquals("https://b.tv/x.ts", Scanner.resolve(base, "https://b.tv/x.ts"))
        assertEquals("a.tv", Scanner.host(base))
    }
}
