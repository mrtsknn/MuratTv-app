package com.murat.yakalatv

/**
 * Bir kanal açılmazsa geçilecek yedek linkler. Listede hiçbir şey birleştirilmez; her link yine ayrı satırdır.
 *
 * Bir link başka bir kanalın yedeği sayılırsa:
 * - adları aynıdır (HD/FHD, parantez ve "Yedek N" ekleri hariç) ve aynı kategoridedirler,
 * - ve ya gerçek bir TV kanalıdır (yayın akışında o adla kanal var) ya da adında "Yedek" geçer,
 * - ve dosya linki (.mp4, .mkv…) değildir: bunlar bir programın kaydıdır, canlı kanal değil.
 * Böylece aynı adı taşıyan farklı program yayınları ("TRT Canlı Akış" gibi) birbirinin yedeği sayılmaz.
 * Oynatıcı ayrıca açılan yedeğin canlı yayın olduğunu doğrular.
 */
object Backups {
    private val backupWord = Regex("(?i)\\byedek\\s*\\d*\\b")
    private val vodExt = Regex("(?i)\\.(mp4|mkv|avi|mov|m4v|webm|flv|wmv)$")

    private val seqNo = Regex("#\\s*\\d+")
    private val plus = Regex("\\+")

    private fun bare(name: String) = name.replace(backupWord, " ").replace(seqNo, " ")

    /**
     * Kümeleme anahtarı. "beIN Sports 1 (1080p Local Uydu) #3" gibi sıra ekleri de atılır; yoksa aynı kanallar eşleşmez.
     * "+" anlamlıdır: Epg.key onu harf olmadığı için atar, o zaman "S Sport+" ile "S Sport", "FilmBox+" ile "FilmBox",
     * "Canal+" ile "Canal" aynı kanal sayılır ve birinin linki ötekinin kaynaklarına karışır.
     */
    fun key(name: String) = Epg.key(bare(name).replace(plus, " plus "))

    /** Yayın akışında aramak için anahtar (Epg ile aynı kurallar; "+" atılır). */
    private fun epgKey(name: String) = Epg.key(bare(name))

    /** [ch]'nin geçerli kopya kümesi (kendisi dahil, liste sırasıyla); kopyası yoksa yalnızca kendisi. */
    fun cluster(ch: Channel, clusters: Map<String, List<Channel>>, isRealChannel: (String) -> Boolean): List<Channel> {
        if (isVod(ch.url)) return listOf(ch)
        val k = key(ch.name)
        val c = clusters["${ch.group}|$k"] ?: return listOf(ch)
        if (k.isEmpty() || (!isRealChannel(epgKey(ch.name)) && c.none { isMarkedBackup(it.name) })) return listOf(ch)
        return c.filter { !isVod(it.url) }
    }

    fun isVod(url: String) = vodExt.containsMatchIn(url.substringBefore('?').substringBefore('#'))

    private fun isMarkedBackup(name: String) = backupWord.containsMatchIn(name)

    /** Kategori + ad anahtarına göre kümeler; tek elemanlılar atılır. */
    fun clusters(channels: List<Channel>): Map<String, List<Channel>> =
        channels.groupBy { "${it.group}|${key(it.name)}" }.filterValues { it.size > 1 }

    /** [ch] için denenecek linkler: önce kendisi, sonra geçerli yedekler (açılmadığı bilinenler en sona). */
    fun sourcesFor(
        ch: Channel,
        clusters: Map<String, List<Channel>>,
        isRealChannel: (String) -> Boolean,
        isBroken: (Channel) -> Boolean,
    ): List<Channel> {
        // Program kaydının yerine canlı kanal açılmaz
        if (isVod(ch.url)) return listOf(ch)
        val k = key(ch.name)
        val cluster = clusters["${ch.group}|$k"] ?: return listOf(ch)
        if (k.isEmpty() || (!isRealChannel(epgKey(ch.name)) && cluster.none { isMarkedBackup(it.name) })) return listOf(ch)
        val others = cluster.filter { it != ch && !isVod(it.url) }.sortedBy { isBroken(it) }
        return listOf(ch) + others
    }
}
