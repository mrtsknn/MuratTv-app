package com.murat.yakalatv

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Liste bir GitHub Gist'iyse, silinen kanalı Gist'ten de çıkarır.
 * Kanalın tüm satırları Gist'teki silinen.txt'ye taşınır: gist_guncelle.py bunları bir daha yüklemez,
 * "Silinenleri geri getir" de buradan listeye geri koyar. Böylece hiçbir silme kalıcı değildir.
 */
object Gist {
    const val DELETED_FILE = "silinen.txt"

    fun idFrom(listUrl: String): String? =
        Regex("gist\\.githubusercontent\\.com/[^/]+/([0-9a-fA-F]+)").find(listUrl)?.groupValues?.get(1)

    suspend fun deleteChannel(gistId: String, token: String, channelUrl: String) {
        val g = read(gistId, token)
        val id = linkId(channelUrl)
        val block = M3u.blocks(g.playlist).firstOrNull { linkId(it.first) == id }?.second
        val already = M3u.blocks(g.deleted).any { linkId(it.first) == id }
        val deleted = if (block == null || already) g.deleted else g.deleted + block.joinToString("\n") + "\n"
        write(gistId, token, g.name, M3u.remove(g.playlist, channelUrl), deleted)
    }

    /** silinen.txt'deki tüm kanalları listenin sonuna geri ekler; geri gelen kanal sayısını döner. */
    suspend fun restoreAll(gistId: String, token: String): Int {
        val g = read(gistId, token)
        val present = M3u.blocks(g.playlist).map { linkId(it.first) }.toSet()
        val back = M3u.blocks(g.deleted).filter { linkId(it.first) !in present }
        if (back.isEmpty() && g.deleted.isBlank()) return 0
        val playlist = g.playlist.trimEnd() + "\n" + back.joinToString("") { it.second.joinToString("\n") + "\n" }
        write(gistId, token, g.name, playlist, "")
        return back.size
    }

    const val SETTINGS_FILE = "murattv.json"
    /** Uygulamadan yapılan kanal düzenlemeleri: kimlik → {name, group, logo}. gist_guncelle.py yerel listeye uygular. */
    const val EDITS_FILE = "duzenlemeler.json"

    /** Kanalın adını/kategorisini/logosunu Gist'teki listede değiştirir ve düzenlemeyi kalıcı olarak kaydeder. */
    suspend fun saveEdit(gistId: String, token: String, id: String, e: ChannelEdit) {
        val h = headers(token)
        val got = httpSend("GET", "https://api.github.com/gists/$gistId", h, null)
        check(got.code == 200) { failure(got.code, "Gist okunamadı") }
        val files = Json.parseToJsonElement(got.body).jsonObject["files"]!!.jsonObject
        val (name, file) = files.entries.firstOrNull { it.key.endsWith(".m3u", ignoreCase = true) }
            ?: error("Gist'te .m3u dosyası yok")
        val playlist = file.jsonObject.contentOf(h)
        val edits = files[EDITS_FILE]?.jsonObject?.contentOf(h)
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        // Önceki düzenlemeyle birleştir: bu sefer değiştirilmeyen alan eski düzenlemesini korur
        val old = edits[id]?.jsonObject
        val merged = buildJsonObject {
            (e.name ?: old?.get("name")?.jsonPrimitive?.content)?.let { put("name", it) }
            (e.group ?: old?.get("group")?.jsonPrimitive?.content)?.let { put("group", it) }
            (e.logo ?: old?.get("logo")?.jsonPrimitive?.content)?.let { put("logo", it) }
        }
        val allEdits = JsonObject(edits + (id to merged))
        patch(gistId, token, mapOf(name to M3u.edit(playlist, id, e), EDITS_FILE to allEdits.toString()))
    }

    /** Gist'teki düzenlemeler (başka cihazda yapılanlar da) okunur; token yoksa ham adresten. */
    suspend fun readEdits(listUrl: String): Map<String, ChannelEdit> {
        val res = httpSend("GET", listUrl.substringBeforeLast('/') + "/$EDITS_FILE?t=${nowMs()}", emptyMap(), null)
        if (res.code != 200) return emptyMap()
        val obj = runCatching { Json.parseToJsonElement(res.body).jsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapValues { (_, v) ->
            val o = v.jsonObject
            ChannelEdit(
                o["name"]?.jsonPrimitive?.content,
                o["group"]?.jsonPrimitive?.content,
                o["logo"]?.jsonPrimitive?.content,
            )
        }
    }

    private suspend fun patch(gistId: String, token: String, files: Map<String, String>) {
        val body = buildJsonObject {
            putJsonObject("files") { files.forEach { (n, c) -> putJsonObject(n) { put("content", c) } } }
        }
        val res = httpSend(
            "PATCH", "https://api.github.com/gists/$gistId",
            headers(token) + ("Content-Type" to "application/json"), body.toString(),
        )
        check(res.code == 200) { failure(res.code, "Gist güncellenemedi") }
    }

    /** Cihazlar arası ayarlar (sıralama). Token yoksa Gist'in ham adresinden okunur. */
    suspend fun readSettings(listUrl: String, token: String): String? {
        val id = idFrom(listUrl) ?: return null
        if (token.isNotBlank()) {
            val got = httpSend("GET", "https://api.github.com/gists/$id", headers(token), null)
            if (got.code != 200) return null
            val f = Json.parseToJsonElement(got.body).jsonObject["files"]!!.jsonObject[SETTINGS_FILE] ?: return null
            return f.jsonObject.contentOf(headers(token))
        }
        // Ham adres önbelleğe takılmasın diye sona zaman eklenir
        val res = httpSend("GET", listUrl.substringBeforeLast('/') + "/$SETTINGS_FILE?t=${nowMs()}", emptyMap(), null)
        return res.body.takeIf { res.code == 200 }
    }

    suspend fun writeSettings(gistId: String, token: String, json: String) {
        val body = buildJsonObject { putJsonObject("files") { putJsonObject(SETTINGS_FILE) { put("content", json) } } }
        val res = httpSend(
            "PATCH", "https://api.github.com/gists/$gistId",
            headers(token) + ("Content-Type" to "application/json"), body.toString(),
        )
        check(res.code == 200) { failure(res.code, "Ayarlar Gist'e yazılamadı") }
    }

    /** Uzun süredir açılmayan linkler: "ölmeye başladığı an|link kimliği". gist_guncelle.py bunları yeniden dener. */
    const val DEAD_FILE = "olu.txt"

    suspend fun readDead(listUrl: String): Map<String, Long> {
        val res = httpSend("GET", listUrl.substringBeforeLast('/') + "/$DEAD_FILE?t=${nowMs()}", emptyMap(), null)
        if (res.code != 200) return emptyMap()
        return res.body.lineSequence().filter { '|' in it && !it.startsWith("#") }.mapNotNull { line ->
            line.substringBefore('|').toLongOrNull()?.let { line.substringAfter('|').trim() to it }
        }.toMap()
    }

    suspend fun writeDead(gistId: String, token: String, dead: Map<String, Long>) {
        val text = "# MuratTv: 3 günden uzun süredir açılmayan linkler (ölmeye başladığı an|link)\n" +
            dead.entries.joinToString("") { "${it.value}|${it.key}\n" }
        val body = buildJsonObject { putJsonObject("files") { putJsonObject(DEAD_FILE) { put("content", text) } } }
        val res = httpSend(
            "PATCH", "https://api.github.com/gists/$gistId",
            headers(token) + ("Content-Type" to "application/json"), body.toString(),
        )
        check(res.code == 200) { failure(res.code, "Ölü linkler Gist'e yazılamadı") }
    }

    private class Files(val name: String, val playlist: String, val deleted: String)

    private fun headers(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "User-Agent" to "MuratTv",
    )

    private suspend fun read(gistId: String, token: String): Files {
        val h = headers(token)
        val got = httpSend("GET", "https://api.github.com/gists/$gistId", h, null)
        check(got.code == 200) { failure(got.code, "Gist okunamadı") }
        val files = Json.parseToJsonElement(got.body).jsonObject["files"]!!.jsonObject
        val (name, file) = files.entries.firstOrNull { it.key.endsWith(".m3u", ignoreCase = true) }
            ?: error("Gist'te .m3u dosyası yok")
        return Files(name, file.jsonObject.contentOf(h), files[DELETED_FILE]?.jsonObject?.contentOf(h).orEmpty())
    }

    private suspend fun write(gistId: String, token: String, name: String, playlist: String, deleted: String) {
        val body = buildJsonObject {
            putJsonObject("files") {
                putJsonObject(name) { put("content", playlist) }
                // Boş dosya Gist'te tutulamaz; boşalınca silinir
                if (deleted.isBlank()) put(DELETED_FILE, JsonNull) else putJsonObject(DELETED_FILE) { put("content", deleted) }
            }
        }
        val res = httpSend(
            "PATCH", "https://api.github.com/gists/$gistId",
            headers(token) + ("Content-Type" to "application/json"), body.toString(),
        )
        check(res.code == 200) { failure(res.code, "Gist güncellenemedi") }
    }

    private fun failure(code: Int, what: String) =
        if (code == 401 || code == 403 || code == 404) "GitHub token geçersiz ya da gist yetkisi yok" else "$what (HTTP $code)"

    // Büyük dosyalarda API içeriği kırpar; o zaman ham adresten okunur
    private suspend fun JsonObject.contentOf(headers: Map<String, String>): String {
        val truncated = this["truncated"]?.jsonPrimitive?.booleanOrNull == true
        if (!truncated) return this["content"]?.jsonPrimitive?.content.orEmpty()
        val raw = this["raw_url"]!!.jsonPrimitive.content
        return httpSend("GET", raw, headers, null).body
    }
}
