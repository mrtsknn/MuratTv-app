package com.murat.yakalatv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpgTest {
    @Test
    fun parsesXmltvTimeWithOffset() {
        // 2026-10-26 06:00 +03:00 = 03:00 UTC
        assertEquals(1792983600000L, Epg.time("20261026060000 +0300"))
        assertEquals(1792994400000L, Epg.time("20261026060000 +0000"))
    }

    @Test
    fun keyMatchesPlaylistAndEpgNames() {
        assertEquals(Epg.key("SHOW TV HD"), Epg.key("Show TV"))
        assertEquals(Epg.key("TRT 1 HD"), Epg.key("TRT1"))
        assertEquals(Epg.key("TV8 HD"), Epg.key("TV 8"))
        assertEquals(Epg.key("BENGÜ TÜRK"), Epg.key("Bengü Türk"))
        assertEquals(Epg.key("SİNEMA TV HD"), Epg.key("Sinema TV (1080p)"))
    }

    @Test
    fun nowAndNext() {
        val xml = """
            <tv>
            <channel id="SHOW.TV.tr"><display-name lang="tr">SHOW TV HD</display-name></channel>
            <programme start="20261026060000 +0300" stop="20261026080000 +0300" channel="SHOW.TV.tr"><title lang="tr">Sabah &amp; Haber</title></programme>
            <programme start="20261026080000 +0300" stop="20261026100000 +0300" channel="SHOW.TV.tr"><title lang="tr">Dizi</title></programme>
            </tv>
        """.trimIndent()
        val t0 = Epg.time("20261026070000 +0300")!!
        val epg = Epg.parse(xml, t0 - 86_400_000, t0 + 86_400_000)
        val ch = Channel("Show TV", "Ulusal", "http://x", null, null, null)
        val (now, next) = epg.nowNext(ch, t0)
        assertEquals("Sabah & Haber", now?.title)
        assertEquals("Dizi", next?.title)
        assertNull(epg.nowNext(ch.copy(name = "Başka"), t0).first)
    }
}
