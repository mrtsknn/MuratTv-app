package com.murat.yakalatv

const val DEFAULT_EPG_URL = "https://epgshare01.online/epgshare01/epg_ripper_TR1.xml.gz"

class Programme(val start: Long, val stop: Long, val title: String)

/**
 * XMLTV yayın akışı. Listede tvg-id olmadığından kanallar adlarıyla eşleştirilir:
 * "SHOW TV HD" ile "Show TV", "TRT 1 HD" ile "TRT1" aynı sayılır.
 */
class Epg(
    private val byKey: Map<String, List<Programme>>,
    /** Yayın akışında adı geçen tüm kanallar (programı olmasa da): gerçek TV kanalı olduğunu doğrulamak için. */
    private val knownKeys: Set<String> = byKey.keys,
) {
    /** Şu anki ve sıradaki program. */
    fun nowNext(ch: Channel, now: Long): Pair<Programme?, Programme?> {
        val list = byKey[key(ch.name)] ?: return null to null
        val i = list.indexOfFirst { it.stop > now }
        if (i < 0) return null to null
        val cur = list[i].takeIf { it.start <= now }
        return cur to list.getOrNull(if (cur == null) i else i + 1)
    }

    val isEmpty get() = byKey.isEmpty()

    /** Bu ad anahtarıyla yayın akışında kanal var mı (gerçek bir TV kanalı mı)? */
    fun hasKey(key: String) = key in knownKeys || key in byKey

    companion object {
        /** Ad eşleştirme anahtarı: Türkçe harfler sadeleşir, kalite ekleri ve parantezler atılır. */
        fun key(name: String): String {
            var s = name.lowercase()
                .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
                .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c').replace("i̇", "i")
            s = s.replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            s = s.replace(Regex("\\b(hd|fhd|uhd|sd|4k|hevc|h265|tr|turkiye|\\d{3,4}p)\\b"), " ")
            return s.filter { it.isLetterOrDigit() }
        }

        /** XMLTV metnini okur; yalnızca [from]..[to] arasına değen programlar tutulur. */
        fun parse(xml: String, from: Long, to: Long): Epg {
            val names = HashMap<String, MutableList<String>>() // kanal id -> görünen adlar
            var i = 0
            while (true) {
                val c = xml.indexOf("<channel ", i).takeIf { it >= 0 } ?: break
                val end = xml.indexOf("</channel>", c).takeIf { it >= 0 } ?: break
                val id = attr(xml, c, "id") ?: ""
                var d = c
                while (true) {
                    val dn = xml.indexOf("<display-name", d).takeIf { it in 0 until end } ?: break
                    val gt = xml.indexOf('>', dn)
                    val close = xml.indexOf("</display-name>", gt)
                    names.getOrPut(id) { ArrayList() } += unescape(xml.substring(gt + 1, close))
                    d = close
                }
                i = end
            }

            val progs = HashMap<String, MutableList<Programme>>()
            i = 0
            while (true) {
                val p = xml.indexOf("<programme ", i).takeIf { it >= 0 } ?: break
                val end = xml.indexOf("</programme>", p).takeIf { it >= 0 } ?: break
                i = end
                val start = time(attr(xml, p, "start")) ?: continue
                val stop = time(attr(xml, p, "stop")) ?: continue
                if (stop < from || start > to) continue
                val t = xml.indexOf("<title", p).takeIf { it in 0 until end } ?: continue
                val gt = xml.indexOf('>', t)
                val title = unescape(xml.substring(gt + 1, xml.indexOf("</title>", gt)))
                progs.getOrPut(attr(xml, p, "channel") ?: continue) { ArrayList() } += Programme(start, stop, title)
            }

            val byKey = HashMap<String, List<Programme>>()
            for ((id, list) in progs) {
                list.sortBy { it.start }
                for (n in names[id].orEmpty() + id.substringBeforeLast('.')) byKey.getOrPut(key(n)) { list }
            }
            val known = names.flatMap { (id, n) -> n + id.substringBeforeLast('.') }.map(::key).filter { it.isNotEmpty() }.toSet()
            return Epg(byKey, known)
        }

        private fun attr(xml: String, tagStart: Int, name: String): String? {
            val tagEnd = xml.indexOf('>', tagStart)
            val k = xml.indexOf(" $name=\"", tagStart).takeIf { it in 0 until tagEnd } ?: return null
            val v = k + name.length + 3
            return xml.substring(v, xml.indexOf('"', v))
        }

        /** "20261026060000 +0300" → epoch ms (UTC). */
        fun time(s: String?): Long? {
            if (s == null || s.length < 14) return null
            val y = s.substring(0, 4).toInt()
            val mo = s.substring(4, 6).toInt()
            val d = s.substring(6, 8).toInt()
            val h = s.substring(8, 10).toInt()
            val mi = s.substring(10, 12).toInt()
            val se = s.substring(12, 14).toInt()
            val tz = s.substring(14).trim()
            val off = if (tz.length >= 5) {
                val sign = if (tz[0] == '-') -1 else 1
                sign * (tz.substring(1, 3).toInt() * 60 + tz.substring(3, 5).toInt())
            } else 0
            return ((daysFromCivil(y, mo, d) * 86400L + h * 3600 + mi * 60 + se) - off * 60L) * 1000
        }

        // Howard Hinnant'ın tarih algoritması: 1970-01-01'den bu yana gün
        private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097L + doe - 719468
        }

        private fun unescape(s: String) = s.trim()
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")
    }
}
