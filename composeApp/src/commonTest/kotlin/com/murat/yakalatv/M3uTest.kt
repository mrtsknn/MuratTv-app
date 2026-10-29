package com.murat.yakalatv

import kotlin.test.Test
import kotlin.test.assertEquals

class M3uTest {
    private val sample = """
        #EXTM3U
        #EXTINF:-1 tvg-logo="a.png" group-title="TR • Ulusal",Star TV
        #EXTVLCOPT:http-user-agent=UA1
        http://a/star.m3u8
        #EXTINF:-1 group-title="Haber, Gündem",Haber, Kanal
        http://b/haber.m3u8
        #EXTINF:-1 group-title="Spor",Spor 1
        #EXTVLCOPT:http-user-agent=UA3
        http://c/spor.m3u8
    """.trimIndent()

    @Test
    fun parsesNamesGroupsAndUserAgents() {
        val list = M3u.parse(sample)
        assertEquals(listOf("Star TV", "Haber, Kanal", "Spor 1"), list.map { it.name })
        assertEquals(listOf("TR • Ulusal", "Haber, Gündem", "Spor"), list.map { it.group })
        assertEquals(listOf("UA1", null, "UA3"), list.map { it.userAgent })
    }

    @Test
    fun removeDropsOnlyThatChannelWithItsOptionLines() {
        val out = M3u.remove(sample, "http://a/star.m3u8")
        assertEquals(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Haber, Gündem",Haber, Kanal
            http://b/haber.m3u8
            #EXTINF:-1 group-title="Spor",Spor 1
            #EXTVLCOPT:http-user-agent=UA3
            http://c/spor.m3u8

            """.trimIndent(),
            out,
        )
        assertEquals(listOf("Haber, Kanal", "Spor 1"), M3u.parse(out).map { it.name })
    }

    @Test
    fun removeMiddleAndLastChannel() {
        val out = M3u.remove(M3u.remove(sample, "http://b/haber.m3u8"), "http://c/spor.m3u8")
        assertEquals(listOf("Star TV"), M3u.parse(out).map { it.name })
        assertEquals("UA1", M3u.parse(out).single().userAgent)
    }

    @Test
    fun blocksKeepOptionLinesForRestore() {
        val b = M3u.blocks(sample)
        assertEquals(listOf("http://a/star.m3u8", "http://b/haber.m3u8", "http://c/spor.m3u8"), b.map { it.first })
        assertEquals(3, b[0].second.size)
        // Silinip geri eklenen blok, kanalı aynı ayarlarla geri getirir
        val restored = M3u.remove(sample, "http://a/star.m3u8") + b[0].second.joinToString("\n") + "\n"
        assertEquals("UA1", M3u.parse(restored).first { it.name == "Star TV" }.userAgent)
    }

    @Test
    fun editChangesNameGroupAndLogoOfOnlyThatChannel() {
        val out = M3u.edit(sample, "http://b/haber.m3u8", ChannelEdit(name = "Haber TV", group = "Haber", logo = "http://l/h.png"))
        val list = M3u.parse(out)
        assertEquals(listOf("Star TV", "Haber TV", "Spor 1"), list.map { it.name })
        assertEquals(listOf("TR • Ulusal", "Haber", "Spor"), list.map { it.group })
        assertEquals(listOf("a.png", "http://l/h.png", null), list.map { it.logo })
        // Ayar satırları (user-agent) yerinde kalır
        assertEquals(listOf("UA1", null, "UA3"), list.map { it.userAgent })
    }

    @Test
    fun editMatchesTokenizedLinksAndCanClearLogo() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-logo=\"x.png\" group-title=\"A\",Kanal 359\nhttp://h/359.ts?token=ESKI\n"
        val out = M3u.edit(text, linkId("http://h/359.ts?token=YENI"), ChannelEdit(name = "TRT Spor", logo = ""))
        val ch = M3u.parse(out).single()
        assertEquals("TRT Spor", ch.name)
        assertEquals("A", ch.group)
        assertEquals(null, ch.logo)
        assertEquals("http://h/359.ts?token=ESKI", ch.url)
    }

    @Test
    fun removeMatchesTokenizedLinks() {
        val text = "#EXTM3U\n#EXTINF:-1,A\nhttp://h/1.ts?token=ESKI\n#EXTINF:-1,B\nhttp://h/2.ts\n"
        assertEquals(listOf("B"), M3u.parse(M3u.remove(text, "http://h/1.ts?token=YENI")).map { it.name })
    }

    @Test
    fun gistIdFromRawUrl() {
        assertEquals("0123abcd", Gist.idFrom("https://gist.githubusercontent.com/kisi/0123abcd/raw/playlist.m3u"))
        assertEquals(null, Gist.idFrom("http://example.com/list.m3u"))
    }

    @Test
    fun parsesListWithBom() {
        val text = "﻿#EXTM3U\n#EXTINF:-1 group-title=\"Haber\",Kanal\nhttp://a/b.m3u8\n"
        assertEquals("Kanal", M3u.parse(text).single().name)
    }
}
