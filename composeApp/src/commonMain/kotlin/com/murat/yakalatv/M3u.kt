package com.murat.yakalatv

data class Channel(
    val name: String,
    val group: String,
    val url: String,
    val userAgent: String?,
    val referrer: String?,
    val logo: String?,
) {
    /** Kalıcı kimlik: token'lar genelde linkin ? sonrasında değişir; sıralama/silme/düzenleme bununla eşleşir. */
    val id: String get() = linkId(url)
}

/** m3u_yakala.py'deki _anahtar ile aynı: sorgu ve # kısmı atılmış link. */
fun linkId(url: String) = url.trim().substringBefore('?').substringBefore('#')

/** Kullanıcının bir kanalda değiştirdiği alanlar; null olan alan listedeki haliyle kalır. */
data class ChannelEdit(val name: String? = null, val group: String? = null, val logo: String? = null)

object M3u {
    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>(512)
        var info: String? = null
        var ua: String? = null
        var ref: String? = null
        for (raw in text.lineSequence()) {
            // Başta UTF-8 BOM olan listelerde #EXTM3U/#EXTINF tanınsın
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    info = line; ua = attr(line, "user-agent"); ref = attr(line, "referrer")
                }
                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> ua = line.substringAfter('=')
                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) -> ref = line.substringAfter('=')
                line.startsWith("#") -> Unit
                else -> {
                    val i = info
                    out += Channel(
                        name = i?.let(::title)?.ifBlank { null } ?: line.substringAfterLast('/'),
                        group = i?.let { attr(it, "group-title") }?.ifBlank { null } ?: "Diğer",
                        url = line,
                        userAgent = ua,
                        referrer = ref,
                        logo = i?.let { attr(it, "tvg-logo") }?.ifBlank { null },
                    )
                    info = null; ua = null; ref = null
                }
            }
        }
        return out
    }

    /** Metni kanal bloklarına ayırır: link → #EXTINF/#EXTVLCOPT satırları ve link. #EXTM3U ve boş satırlar atlanır. */
    fun blocks(text: String): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        val block = ArrayList<String>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            when {
                t.isEmpty() || t.startsWith("#EXTM3U", ignoreCase = true) -> Unit
                t.startsWith("#") -> block += line
                else -> {
                    out += t to (block + line)
                    block.clear()
                }
            }
        }
        return out
    }

    /** Kimliği [url] ile aynı kanal(lar)ı, #EXTINF ve #EXTVLCOPT satırlarıyla birlikte metinden çıkarır. */
    fun remove(text: String, url: String): String = rewrite(text, linkId(url)) { null }

    /** Kimliği [id] olan kanal(lar)ın adını, kategorisini ve logosunu #EXTINF satırında değiştirir. */
    fun edit(text: String, id: String, e: ChannelEdit): String = rewrite(text, id) { block ->
        block.map { line -> if (line.trim().startsWith("#EXTINF", ignoreCase = true)) editInfo(line.trim(), e) else line }
    }

    /** Eşleşen blokları [change] ile değiştirir (null dönerse blok silinir); diğer her şey aynen kalır. */
    private fun rewrite(text: String, id: String, change: (List<String>) -> List<String>?): String {
        val out = StringBuilder(text.length + 64)
        val block = ArrayList<String>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("#EXTM3U", ignoreCase = true) -> out.append(line).append('\n')
                t.isEmpty() -> Unit
                t.startsWith("#") -> block += line
                else -> {
                    val full = block + line
                    (if (linkId(t) == id) change(full) else full)?.forEach { out.append(it).append('\n') }
                    block.clear()
                }
            }
        }
        block.forEach { out.append(it).append('\n') }
        return out.toString()
    }

    private fun editInfo(line: String, e: ChannelEdit): String {
        var l = line
        e.group?.let { l = setAttr(l, "group-title", it) }
        e.logo?.let { l = setAttr(l, "tvg-logo", it) }
        e.name?.let { name ->
            val comma = titleComma(l)
            l = if (comma < 0) "$l,$name" else l.substring(0, comma + 1) + name
        }
        return l
    }

    /** Özniteliği değiştirir, yoksa ekler; boş değer özniteliği kaldırır. */
    private fun setAttr(line: String, key: String, value: String): String {
        val v = value.replace("\"", "").trim()
        val start = line.indexOf("$key=\"", ignoreCase = true)
        if (start >= 0) {
            val end = line.indexOf('"', start + key.length + 2)
            val before = line.substring(0, start).trimEnd()
            val after = line.substring(end + 1)
            return if (v.isEmpty()) before + after else "$before $key=\"$v\"$after"
        }
        if (v.isEmpty()) return line
        val head = Regex("^#EXTINF:\\s*-?\\d+", RegexOption.IGNORE_CASE).find(line)?.value ?: return line
        return head + " $key=\"$v\"" + line.substring(head.length)
    }

    // Kanal adı, özniteliklerden sonraki ilk virgülden sonra gelir; öznitelik değerleri virgül içerebilir
    private fun titleComma(line: String): Int {
        val afterAttrs = line.lastIndexOf('"').let { if (it < 0) line.indexOf(':') else it }
        return line.indexOf(',', afterAttrs.coerceAtLeast(0))
    }

    private fun title(line: String): String {
        val comma = titleComma(line)
        return if (comma < 0) "" else line.substring(comma + 1).trim()
    }

    private fun attr(line: String, key: String): String? {
        val start = line.indexOf("$key=\"", ignoreCase = true)
        if (start < 0) return null
        val from = start + key.length + 2
        val end = line.indexOf('"', from)
        return if (end < 0) null else line.substring(from, end)
    }
}
