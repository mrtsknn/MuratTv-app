package com.murat.yakalatv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupsTest {
    private fun ch(name: String, group: String, url: String) = Channel(name, group, url, null, null, null)

    private val list = listOf(
        ch("TRT 1", "Ulusal", "http://a/trt1.m3u8"),
        ch("TRT 1 HD", "Ulusal", "http://b/trt1.ts"),
        ch("TRT 1 (UAE)", "Ulusal", "http://c/trt1.m3u8"),
        ch("TRT 1", "Sinema & Dizi", "http://d/trt1.m3u8"),
        ch("TRT Canlı Akış", "Ulusal", "http://e/1.m3u8"),
        ch("TRT Canlı Akış", "Ulusal", "http://e/2.m3u8"),
        ch("CINE 1 HD", "Sinema & Dizi", "http://f/cine.m3u8"),
        ch("Cine 1 Yedek 1", "Sinema & Dizi", "http://f/cine_b1.m3u8"),
        ch("Cine 1 Yedek 2", "Sinema & Dizi", "http://f/cine_b2.m3u8"),
        ch("Star TV", "Ulusal", "http://g/star.m3u8"),
        ch("Star TV", "Ulusal", "http://g/dizi-bolum-5.mp4?x=1"),
    )
    private val clusters = Backups.clusters(list)
    private val tvChannels = setOf(Epg.key("TRT 1 HD"), Epg.key("STAR TV HD"))

    private fun sources(i: Int, broken: Set<String> = emptySet()) =
        Backups.sourcesFor(list[i], clusters, { it in tvChannels }, { it.url in broken }).map { it.url }

    @Test
    fun realChannelGetsBackupsOnlyFromSameGroup() {
        assertEquals(listOf("http://a/trt1.m3u8", "http://b/trt1.ts", "http://c/trt1.m3u8"), sources(0))
    }

    @Test
    fun sameNamedProgramStreamsAreNotBackups() {
        assertEquals(listOf("http://e/1.m3u8"), sources(4))
    }

    @Test
    fun namesMarkedYedekAreBackupsEvenWithoutEpg() {
        assertEquals(listOf("http://f/cine.m3u8", "http://f/cine_b1.m3u8", "http://f/cine_b2.m3u8"), sources(6))
        assertEquals(listOf("http://f/cine_b2.m3u8", "http://f/cine.m3u8", "http://f/cine_b1.m3u8"), sources(8))
    }

    @Test
    fun fileLinksAreNeverBackups() {
        assertEquals(listOf("http://g/star.m3u8"), sources(9))
        // Program kaydının da yedeği olmaz
        assertEquals(listOf("http://g/dizi-bolum-5.mp4?x=1"), sources(10))
    }

    @Test
    fun similarNamedChannelsAreNotMerged() {
        // Adı kısalınca ya da sembolü atılınca çakışabilecek farklı kanallar
        val pairs = listOf(
            "TRT 1" to "TRT 1 Müzik", "Show TV" to "Show Max", "Star TV" to "Star Türk", "Star TV" to "Star Life",
            "TV8" to "TV8,5", "TV8" to "TV 8.5", "NTV" to "KNTV", "24 TV" to "Gurbet 24 TV", "360" to "Canal+ 360",
            "S Sport" to "S Sport+", "S Sport" to "S Sport 2", "FilmBox" to "FilmBox+", "Canal+ Sport" to "Canal Sport",
            "TRT Spor" to "TRT Spor Yıldız", "TRT Spor" to "TRT Spor 2", "Kanal 7" to "Kanal 7 Avrupa",
            "beIN Sports 1" to "beIN Sports 1 Fransa", "Tivibu Spor 3" to "Tivibu Spor 5",
        )
        for ((a, b) in pairs) {
            assertTrue(Backups.key(a) != Backups.key(b), "$a ile $b aynı kümeye düşmemeli")
            assertTrue(Backups.key(a) != Backups.key("$b Yedek 3"), "$a ile $b Yedek 3 aynı kümeye düşmemeli")
        }
        // Yazım farkları yine aynı kanal
        assertEquals(Backups.key("Crime + Investigation"), Backups.key("Crime + Investigation Yedek 1"))
        assertEquals(Backups.key("CNN Türk"), Backups.key("CNN TÜRK HD"))
        assertEquals(Backups.key("S Sport+"), Backups.key("S SPORT+ HD #2"))
    }

    @Test
    fun plusChannelStillFindsEpgByPlainKey() {
        // "+" kümeleme anahtarında kalır, yayın akışı araması ise Epg.key ile (sembolsüz) yapılır
        val l = listOf(ch("Canal+ Sport", "Spor", "http://p/1.m3u8"), ch("Canal+ Sport HD", "Spor", "http://p/2.m3u8"))
        val epgKeys = setOf(Epg.key("Canal+ Sport"))
        val s = Backups.sourcesFor(l[0], Backups.clusters(l), { it in epgKeys }, { false }).map { it.url }
        assertEquals(listOf("http://p/1.m3u8", "http://p/2.m3u8"), s)
    }

    @Test
    fun brokenBackupsAreTriedLast() {
        assertEquals(listOf("http://a/trt1.m3u8", "http://c/trt1.m3u8", "http://b/trt1.ts"), sources(0, setOf("http://b/trt1.ts")))
    }
}
