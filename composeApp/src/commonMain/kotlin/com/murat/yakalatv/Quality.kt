package com.murat.yakalatv

/** Bir linkin geçmişi: kaç kez açıldı / açılmadı, ortalama açılış süresi, görüntü yüksekliği. */
data class SourceStat(val ok: Int = 0, val fail: Int = 0, val openMs: Long = 0, val height: Int = 0)

/**
 * Aynı kanalın kaynaklarını karşılaştırır: en çok açılan, hızlı ve kaliteli olan öne geçer.
 * Hiç denenmemiş kaynak orta puan alır; böylece yeni linkler de sıra bekler ama dışlanmaz.
 */
object Quality {
    /**
     * Oynatıcıdan gelen sonucu işler. [openMs]: açılış süresi; -1 = önceden yüklendiği için süre yok,
     * -2 = yalnızca görüntü yüksekliği güncellemesi (açılış sayılmaz).
     */
    fun record(old: SourceStat?, ok: Boolean, openMs: Long, height: Int): SourceStat {
        val s = old ?: SourceStat()
        if (!ok) return s.copy(fail = s.fail + 1)
        val h = if (height > 0) height else s.height
        if (openMs == -2L) return s.copy(height = h)
        val ms = when {
            openMs <= 0 -> s.openMs
            s.openMs == 0L -> openMs
            else -> (s.openMs * 3 + openMs) / 4 // son açılışlar daha ağır basar
        }
        return SourceStat(s.ok + 1, s.fail, ms, h)
    }

    fun score(s: SourceStat?, broken: Boolean): Double {
        val base = if (s == null) {
            50.0
        } else {
            val success = (s.ok + 1.0) / (s.ok + s.fail + 2.0)
            val speed = if (s.openMs > 0) (1.0 - s.openMs / 8000.0).coerceIn(0.0, 1.0) else 0.5
            val quality = when {
                s.height >= 1080 -> 1.0
                s.height >= 720 -> 0.75
                s.height >= 480 -> 0.5
                s.height > 0 -> 0.3
                else -> 0.5
            }
            100 * (0.55 * success + 0.25 * speed + 0.20 * quality)
        }
        return if (broken) base - 1000 else base
    }

    /**
     * Açılış sırası: güvenilir ve hızlı açılan önce; kalite burada sayılmaz, daha kaliteli kaynağa
     * kanal açıldıktan sonra arka planda bakılır.
     */
    fun openScore(s: SourceStat?, broken: Boolean): Double {
        val base = if (s == null || s.ok + s.fail == 0) {
            50.0
        } else {
            val success = (s.ok + 1.0) / (s.ok + s.fail + 2.0)
            val speed = if (s.openMs > 0) (1.0 - s.openMs / 8000.0).coerceIn(0.0, 1.0) else 0.5
            100 * (0.5 * success + 0.5 * speed)
        }
        return if (broken) base - 1000 else base
    }

    /** Kaynak listesinde gösterilen kısa açıklama: "1080p · hızlı", "açılmadı", "denenmedi". */
    fun label(s: SourceStat?, broken: Boolean): String {
        if (broken) return "açılmadı"
        if (s == null || (s.ok == 0 && s.fail == 0)) return "denenmedi"
        val parts = buildList {
            if (s.height > 0) add("${s.height}p")
            if (s.openMs > 0) add(if (s.openMs < 2000) "hızlı" else if (s.openMs < 5000) "orta" else "yavaş")
            if (s.fail > s.ok) add("sık takılıyor")
        }
        return parts.joinToString(" · ").ifEmpty { "açıldı" }
    }

    private val uhd = Regex("\\b(4K|UHD|2160P?)\\b")
    private val fhd = Regex("\\b(FHD|1080P?)\\b")
    private val hd = Regex("\\b(HD|720P?)\\b")

    /** Kalite rozeti: 3 = 4K, 2 = FHD, 1 = HD, 0 = yok. Addan ya da ölçülen görüntü yüksekliğinden. */
    fun tier(name: String, height: Int): Int {
        val n = name.uppercase()
        val byName = when {
            uhd.containsMatchIn(n) -> 3
            fhd.containsMatchIn(n) -> 2
            hd.containsMatchIn(n) -> 1
            else -> 0
        }
        val byHeight = when {
            height >= 2000 -> 3
            height >= 1000 -> 2
            height >= 700 -> 1
            else -> 0
        }
        return maxOf(byName, byHeight)
    }

    fun encode(stats: Map<String, SourceStat>) =
        stats.entries.joinToString("\n") { (id, s) -> "${s.ok},${s.fail},${s.openMs},${s.height}|$id" }

    fun decode(text: String?): Map<String, SourceStat> = text.orEmpty().lineSequence().mapNotNull { line ->
        val n = line.substringBefore('|').split(',').mapNotNull { it.toLongOrNull() }
        if (n.size != 4 || '|' !in line) null
        else line.substringAfter('|') to SourceStat(n[0].toInt(), n[1].toInt(), n[2], n[3].toInt())
    }.toMap()
}
