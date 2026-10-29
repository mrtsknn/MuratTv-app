package com.murat.yakalatv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * tv-logo/tv-logos deposunda kanal adına uyan logoları bulur (PC'deki logo_duzelt.py ile aynı kurallar).
 * Arama sırası: Türkiye → International (global) → ABD → İngiltere → Avrupa → diğer bölgeler.
 */
object LogoFinder {
    private const val TREE = "https://api.github.com/repos/tv-logo/tv-logos/git/trees/main?recursive=1"
    const val BASE = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/"
    private val countries = listOf(
        "turkey", "international", "united-states", "united-kingdom", "world-europe",
        "world-middle-east", "world-asia", "world-africa", "world-latin-america",
    )
    private val qualityWords = Regex("\\b(hd|fhd|uhd|sd|4k|hevc|h265|\\d{3,4}p|\\d+fps|yedek \\d*|yedek)\\b")
    private val fileVariants = Regex("(-(hd|hz|white|dark|light|color|alt|new|plus))+$")

    private class Logo(val name: String, val rank: Int, val variant: Boolean, val path: String)

    private var index: List<Logo>? = null

    fun slug(name: String): String {
        var s = name.lowercase()
            .replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i').replace("i̇", "i")
            .replace('ö', 'o').replace('ş', 's').replace('ü', 'u')
        s = s.replace(Regex("\\(.*?\\)|\\[.*?]|#\\d+"), " ").replace(qualityWords, " ")
        return s.replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    /** Aynı kanal için denenecek ad biçimleri: "tv8" / "tv-8", "-channel" / "-tv" ekli ya da eksiz. */
    private fun variants(s: String): Set<String> {
        val base = setOf(s, s.replace(Regex("(?<=[a-z])-(?=\\d)"), ""), s.replace(Regex("(?<=[a-z])(?=\\d)"), "-"))
        return (base + base.flatMap { listOf("$it-channel", "$it-tv", it.replace(Regex("-(channel|tv)$"), "")) })
            .filter { it.isNotEmpty() }.toSet()
    }

    private suspend fun load(token: String): List<Logo> {
        index?.let { return it }
        val headers = buildMap {
            put("User-Agent", "MuratTv")
            if (token.isNotBlank()) put("Authorization", "Bearer $token")
        }
        val res = httpSend("GET", TREE, headers, null)
        check(res.code == 200) { "Logo listesi alınamadı (HTTP ${res.code})" }
        // 2,6 MB'lık metinde Android'in regex motoru çok yavaş; yollar elle taranır
        val logos = withContext(Dispatchers.Default) {
            val out = ArrayList<Logo>(10_000)
            val key = "\"path\":\"countries/"
            var i = res.body.indexOf(key)
            while (i >= 0) {
                val start = i + key.length
                val end = res.body.indexOf('"', start)
                val path = res.body.substring(start, end) // ülke/dosya.png
                val slash = path.indexOf('/')
                if (slash > 0 && path.endsWith(".png") && path.indexOf('/', slash + 1) < 0) {
                    val rank = countries.indexOf(path.substring(0, slash))
                    if (rank >= 0) {
                        val stem = path.substring(slash + 1, path.length - 4).substringBeforeLast('-') // ülke kodu (-tr, -us…) atılır
                        out += Logo(stem, rank, fileVariants.containsMatchIn(stem), "countries/$path")
                    }
                }
                i = res.body.indexOf(key, end)
            }
            out
        }
        index = logos
        return logos
    }

    /** [name] için en uygun logolar (tam adresler); önce tam eşleşenler, sonra adı benzer olanlar. */
    suspend fun find(name: String, token: String, limit: Int = 12): List<String> {
        val logos = load(token)
        val s = slug(name)
        if (s.isEmpty()) return emptyList()
        val wanted = variants(s)
        return withContext(Dispatchers.Default) {
            val exact = logos.filter { it.name in wanted || it.name.replace(fileVariants, "") in wanted }
            // Tam eşleşme yoksa ilk kelimeyle de dene ("beIN Sports 1" → "bein-sports-…")
            val prefixes = wanted + s.substringBeforeLast('-').takeIf { it != s && it.length >= 3 }.let { listOfNotNull(it) }
            val similar = logos.filter { it !in exact && prefixes.any { w -> it.name.startsWith("$w-") } }
            (exact.sortedWith(compareBy({ it.rank }, { it.variant })) +
                similar.sortedWith(compareBy({ it.rank }, { it.name.length })))
                .map { BASE + it.path }.distinct().take(limit)
        }
    }
}
