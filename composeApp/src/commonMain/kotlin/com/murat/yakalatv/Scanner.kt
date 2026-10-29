package com.murat.yakalatv

import kotlin.time.TimeSource

/**
 * Bir linkin tarama sonucu: açıldı mı, ilk veri ne kadar sürede geldi, görüntü yüksekliği (HLS'de biliniyorsa).
 * [blocked]: sunucu erişimi reddetti (401/403); link ölü değil, büyük olasılıkla başka bir ağa/IP'ye bağlı.
 */
data class ScanResult(val ok: Boolean, val ms: Long, val height: Int, val blocked: Boolean = false)

/**
 * Kaynak tarayıcı: linkin başından küçük bir parça indirerek gerçekten yayın verip vermediğini ölçer.
 * HLS'de ana listeden en yüksek çözünürlüklü akış denenir; içinde parça varsa yayın açılıyor sayılır
 * (parçanın kendisini indirmek taramayı uzatıyordu). .ts linkinde doğrudan ilk baytlar.
 */
object Scanner {
    private const val SAMPLE = 32 * 1024
    private const val TIMEOUT = 8_000L
    private val resolution = Regex("RESOLUTION=\\d+x(\\d+)")

    suspend fun probe(ch: Channel): ScanResult {
        val headers = buildMap {
            put("User-Agent", ch.userAgent ?: "MuratTv/0.1 Media3")
            ch.referrer?.let { put("Referer", it) }
        }
        val start = TimeSource.Monotonic.markNow()
        var url = ch.url
        var height = 0
        // En fazla iki liste derinliği: ana liste → akış listesi → parça
        repeat(3) {
            val r = probeStream(url, headers, SAMPLE, TIMEOUT)
            val text = r.text
            if (!r.ok) return ScanResult(false, 0, height, blocked = r.code == 401 || r.code == 403)
            if (text == null || !text.startsWith("#EXTM3U") || "#EXTINF" in text) {
                return ScanResult(true, start.elapsedNow().inWholeMilliseconds, height)
            }
            val next = if ("#EXT-X-STREAM-INF" in text) {
                val best = variants(text).maxByOrNull { it.first } ?: return ScanResult(false, 0, height)
                height = maxOf(height, best.first)
                best.second
            } else {
                text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    ?: return ScanResult(false, 0, height)
            }
            url = resolve(url, next)
        }
        return ScanResult(true, start.elapsedNow().inWholeMilliseconds, height)
    }

    /** Ana listedeki akışlar: (yükseklik, adres). */
    fun variants(text: String): List<Pair<Int, String>> {
        val lines = text.lines().map { it.trim() }
        return lines.mapIndexedNotNull { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
            (resolution.find(line)?.groupValues?.get(1)?.toInt() ?: 0) to uri
        }
    }

    /** Liste içindeki göreli adresi tam adrese çevirir. */
    fun resolve(base: String, ref: String): String = when {
        ref.startsWith("http://") || ref.startsWith("https://") -> ref
        ref.startsWith("/") -> base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + ref
        else -> base.substringBefore('?').substringBeforeLast('/') + "/" + ref
    }

    fun host(url: String) = url.substringAfter("://").substringBefore('/').substringBefore(':')

    /** Panel (Xtream) linki: hesap başına tek bağlantıya izin verebilir; aynı panelde ikinci bağlantı izleneni kesebilir. */
    fun isPanel(url: String) = !url.contains(".m3u8", ignoreCase = true)
}
