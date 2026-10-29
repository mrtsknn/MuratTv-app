package com.murat.yakalatv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualityTest {
    @Test
    fun fastHdReliableSourceWins() {
        val good = Quality.record(Quality.record(null, true, 900, 1080), true, 1100, 0)
        val slow = Quality.record(null, true, 7000, 576)
        val flaky = Quality.record(Quality.record(null, false, 0, 0), false, 0, 0)
        assertTrue(Quality.score(good, false) > Quality.score(slow, false))
        assertTrue(Quality.score(slow, false) > Quality.score(flaky, false))
        // Hiç denenmemiş kaynak, sık takılandan önce denenir
        assertTrue(Quality.score(null, false) > Quality.score(flaky, false))
        // Açılmadığı bilinen kaynak en sona düşer
        assertTrue(Quality.score(good, true) < Quality.score(flaky, false))
    }

    @Test
    fun heightOnlyUpdateDoesNotCountAsOpen() {
        val s = Quality.record(Quality.record(null, true, 1500, 0), true, -2, 1080)
        assertEquals(SourceStat(ok = 1, fail = 0, openMs = 1500, height = 1080), s)
        assertEquals("1080p · hızlı", Quality.label(s, false))
        assertEquals("açılmadı", Quality.label(s, true))
        assertEquals("denenmedi", Quality.label(null, false))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val m = mapOf("http://a/1.ts" to SourceStat(3, 1, 1200, 720), "http://b/x.m3u8" to SourceStat(0, 2, 0, 0))
        assertEquals(m, Quality.decode(Quality.encode(m)))
    }

    @Test
    fun numberedCopiesClusterTogether() {
        // "beIN Sports 1 (1080p Local Uydu) #3" gibi adlar aynı kanal sayılır
        assertEquals(Backups.key("beIN Sports 1"), Backups.key("beIN Sports 1 (1080p Local Uydu) #3"))
        assertEquals(Backups.key("beIN Sports 1"), Backups.key("beIN Sports 1 #12"))
        assertTrue(Backups.key("beIN Sports 1") != Backups.key("beIN Sports 2"))
    }
}
