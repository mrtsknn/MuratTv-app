package com.murat.yakalatv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun nowMs() = Clock.System.now().toEpochMilliseconds()

const val ALL = "Tüm kanallar"
/** "Açılmadı" işaretinin geçerlilik süresi; sonra kaynak yeniden denenir. */
const val BROKEN_TTL_MS = 2 * 3600_000L
private const val SCAN_PARALLEL = 24
private const val SCAN_PER_HOST = 12
/** Arka plan kontrolü: her turda bu kadar link, turlar arası bekleme; tüm liste birkaç saatte bir dolaşılır. */
private const val AUTO_BATCH = 30
private const val AUTO_INTERVAL_MS = 2 * 60_000L
private const val AUTO_RETRY_BROKEN_MS = 3600_000L
private const val AUTO_RETRY_OK_MS = 12 * 3600_000L
/** Bu süre boyunca hiç açılmayan link Gist'e "ölü" diye bildirilir. */
private const val DEAD_AFTER_MS = 3 * 24 * 3600_000L

class AppState(private val scope: CoroutineScope) {
    var url by mutableStateOf(Store.get("url") ?: DEFAULT_URL)
        private set
    var channels by mutableStateOf(emptyList<Channel>())
        private set
    private var listGroups by mutableStateOf(emptyList<String>())
    val groups: List<String>
        get() = listOf(ALL) + listGroups
    var current by mutableStateOf<Channel?>(null)
        private set
    /** Yukarı/aşağı tuşlarıyla gezilen kategori. */
    var group by mutableStateOf(ALL)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var showSetup by mutableStateOf(false)
    /** Gist'i düzenlemek için GitHub token'ı (yalnızca "gist" yetkili). */
    var token by mutableStateOf(Store.get("ghToken").orEmpty())
        private set

    /** Bu cihazda silinen kanallar; Gist henüz güncellenmemiş olsa da gizli kalır. */
    var hidden by mutableStateOf(Store.get("hidden")?.split('\n')?.filter { it.isNotBlank() }?.map(::linkId)?.toSet() ?: emptySet())
        private set
    /** Kullanıcının kanal sıralaması (linkler); boşsa listedeki sıra kullanılır. Yalnızca bu cihazda tutulur. */
    var order by mutableStateOf(Store.get("order")?.split('\n')?.filter { it.isNotBlank() }?.map(::linkId) ?: emptyList())
        private set

    /** XMLTV yayın akışı adresi. */
    var epgUrl by mutableStateOf(Store.get("epgUrl") ?: DEFAULT_EPG_URL)
        private set
    var epg by mutableStateOf<Epg?>(null)
        private set
    /** Bir önceki izlenen kanal ("son kanal" tuşu için). */
    var previous by mutableStateOf<Channel?>(null)
        private set
    private var previousGroup = ALL
    /** Açılamayan kanallar: link → işaretlenme zamanı. 6 saat sonra yeniden denenir. */
    var broken by mutableStateOf(loadBroken())
        private set
    val options = PlayerOptions()
    /** Uygulamadan yapılan kanal düzenlemeleri: kimlik → değişen alanlar. */
    private var edits = loadEdits()

    /** Gizlenenler dahil, kullanıcı sırasına göre dizilmiş tüm kanallar. */
    private var all = emptyList<Channel>()
    private var byGroup = emptyMap<String, List<Channel>>()
    private var backupClusters = emptyMap<String, List<Channel>>()
    /** Sıralamanın son değişiklik zamanı (cihazlar arası eşitleme için). */
    private var syncUpdated = Store.get("syncUpdated")?.toLongOrNull() ?: 0L
    private var pushJob: Job? = null

    /** Linklerin geçmişi (açılma, hız, kalite): aynı kanalın en iyi kaynağını seçmek için. */
    var stats by mutableStateOf(Quality.decode(Store.get("stats")))
        private set
    /** Kullanıcının bir kanal için sabitlediği kaynak: kanal kimliği → kaynak kimliği. */
    var pins by mutableStateOf(
        Store.get("pins").orEmpty().lineSequence().filter { '|' in it }
            .associate { it.substringBefore('|') to it.substringAfter('|') },
    )
        private set
    /** Kanalın son izlemede en iyi bulunan kaynağı (kanal kimliği → kaynak kimliği): sonraki açılışta önce o denenir. */
    private var best = Store.get("best").orEmpty().lineSequence().filter { '|' in it }
        .associate { it.substringBefore('|') to it.substringAfter('|') }

    init {
        options.onResult = { url, ok, ms, h -> reportSource(url, ok, ms, h) }
    }

    /** [ch]'nin kopya kümesi (aynı kanalın tüm linkleri, liste sırasıyla); kopyası yoksa yalnızca kendisi. */
    fun clusterOf(ch: Channel): List<Channel> = Backups.cluster(ch, backupClusters) { epg?.hasKey(it) == true }

    /** Birleştirme açıkken listede kanalı temsil eden satır: kümenin ilk linki (sabit, sıra bununla tutulur). */
    fun repOf(ch: Channel): Channel = if (options.mergeDuplicates) clusterOf(ch).first() else ch

    /** Kullanıcının istemediği kaynaklar (ör. "dijital reklam kuşağındasınız" yazan resmi yayınlar): yalnızca son çare. */
    var avoided by mutableStateOf(Store.get("avoid").orEmpty().lines().filter { it.isNotBlank() }.toSet())
        private set

    fun isAvoided(ch: Channel) = ch.id in avoided

    fun toggleAvoid(ch: Channel, source: Channel) {
        avoided = if (source.id in avoided) avoided - source.id else avoided + source.id
        Store.put("avoid", avoided.joinToString("\n"))
        // İstenmeyen kaynak varsayılan olarak kalmasın
        if (source.id in avoided && pins[repOf(ch).id] == source.id) {
            pins = pins - repOf(ch).id
            Store.put("pins", pins.entries.joinToString("\n") { "${it.key}|${it.value}" })
        }
    }

    fun score(ch: Channel) = Quality.score(stats[ch.id], isBroken(ch)) - if (ch.id in avoided) 500 else 0

    private fun openScore(ch: Channel) = Quality.openScore(stats[ch.id], isBroken(ch)) - if (ch.id in avoided) 500 else 0

    fun sourceLabel(ch: Channel) = (if (ch.id in avoided) "istenmiyor · " else "") +
        if (ch.id in blocked) "bu ağda açılmıyor" else Quality.label(stats[ch.id], isBroken(ch))

    /**
     * Kaynak penceresi: açılmayanlar gizli (hepsi açılmıyorsa ya da oynayansa görünür), en kaliteliden aşağı,
     * aynı kalitede hızlı açılan önce.
     */
    fun pickerSources(ch: Channel, playing: Channel?): List<Channel> {
        val all = clusterOf(ch)
        val alive = all.filter { !isBroken(it) || it.id == playing?.id }.ifEmpty { all }
        return alive.sortedWith(compareByDescending<Channel> { stats[it.id]?.height ?: 0 }.thenByDescending(::openScore))
    }

    private fun members(ch: Channel) = if (options.mergeDuplicates) clusterOf(ch) else listOf(ch)

    /** Satırdaki kalite rozeti: birleşik kanalda en iyi kaynağınki. */
    fun tierOf(ch: Channel) = members(ch).maxOf { Quality.tier(it.name, stats[it.id]?.height ?: 0) }

    /** Logosu olmayan birleşik kanal, kopyalarından birinin logosunu kullanır. */
    fun logoOf(ch: Channel) = ch.logo ?: members(ch).firstNotNullOfOrNull { it.logo }

    /** [ch] için denenecek linkler sırayla. */
    fun sourcesFor(ch: Channel?): List<Channel> {
        ch ?: return emptyList()
        if (options.mergeDuplicates) {
            // Birleşik kanal: sabitlenen kaynak önce, sonra son izlemede en iyi bulunan, sonra en hızlı açılandan
            // yavaşa (kaliteliye sonra geçilir)
            val sorted = clusterOf(ch).sortedByDescending(::openScore)
            val pinned = pins[repOf(ch).id]?.let { id -> sorted.firstOrNull { it.id == id } }
            val list = if (pinned != null) listOf(pinned) + (sorted - pinned) else withBestFirst(ch, sorted)
            return if (options.autoBackup) list else list.take(1)
        }
        if (!options.autoBackup) return listOf(ch)
        val list = Backups.sourcesFor(ch, backupClusters, { epg?.hasKey(it) == true }, ::isBroken)
        // Kendi linkinin açılmadığı biliniyorsa önce çalışan yedek denenir; 10 sn ölü linki beklemeye gerek yok
        return withBestFirst(ch, if (isBroken(ch) && list.drop(1).any { !isBroken(it) }) list.drop(1) + ch else list)
    }

    // Hatırlanan en iyi kaynak sonradan açılmaz ya da istenmez olduysa öne alınmaz; sıra kendiliğinden eskisine döner
    private fun withBestFirst(ch: Channel, list: List<Channel>): List<Channel> {
        val b = best[repOf(ch).id]?.let { id -> list.firstOrNull { it.id == id } }
        return if (b == null || isBroken(b) || b.id in avoided) list else listOf(b) + (list - b)
    }

    /** İzlemenin başındaki kontrolden sonra oynayan kaynak, kanalın en iyisi olarak hatırlanır. */
    fun rememberBest(ch: Channel, source: Channel) {
        val key = repOf(ch).id
        if (best[key] == source.id) return
        best = best + (key to source.id)
        Store.put("best", best.entries.joinToString("\n") { "${it.key}|${it.value}" })
    }

    /** Kanalın diğer link sayısı (birleşikte kaynak sayısı - 1). */
    fun backupCount(ch: Channel) = if (options.mergeDuplicates) clusterOf(ch).size - 1 else sourcesFor(ch).size - 1

    fun pinSource(ch: Channel, source: Channel) {
        pins = pins + (repOf(ch).id to source.id)
        Store.put("pins", pins.entries.joinToString("\n") { "${it.key}|${it.value}" })
    }

    // Oynatıcının her sonucu kaynağın durumunu da günceller: açılan kaynaktan "açılmadı" işareti kalkar
    private fun reportSource(url: String, ok: Boolean, openMs: Long, height: Int) {
        val id = linkId(url)
        stats = stats + (id to Quality.record(stats[id], ok, openMs, height))
        Store.put("stats", Quality.encode(stats))
        if (openMs != -2L) recordResults(mapOf(id to ok))
    }

    /** Link kimliği → son denenme zamanı (tarama, arka plan kontrolü ya da oynatma). */
    private var checked = loadTimes("checked")
    /** Link kimliği → kesintisiz açılmamaya başladığı an; açılınca silinir. 3 günü geçen "uzun süredir ölü" sayılır. */
    private var failSince = loadTimes("failSince")
    /** Sunucunun erişimi reddettiği linkler (401/403): ölü sayılmaz; çoğu başka bir IP'ye bağlı token'lı link. */
    var blocked by mutableStateOf(Store.get("blocked").orEmpty().lines().filter { it.isNotBlank() }.toSet())
        private set

    /**
     * Kaynakların açıldı/açılmadı durumunu toplu yazar: link kimliği → açıldı mı.
     * [probed]: sonuç tarayıcıdan geldi; o zaman [blockedIds] engelli listesinin yerini alır. Oynatıcı engeli ayırt
     * edemediği için onun hatası engelli işaretini silmez.
     */
    private fun recordResults(results: Map<String, Boolean>, probed: Boolean = false, blockedIds: Set<String> = emptySet()) {
        if (results.isEmpty()) return
        val t = nowMs()
        val ok = results.filterValues { it }.keys
        val bad = results.filterValues { !it }.keys
        broken = broken - ok + bad.associateWith { t }
        checked = checked + results.keys.associateWith { t }
        failSince = failSince - ok + bad.filter { it !in failSince }.associateWith { t }
        blocked = if (probed) blocked - results.keys + blockedIds else blocked - ok
        Store.put("broken", broken.entries.joinToString("\n") { "${it.value}|${it.key}" })
        Store.put("checked", checked.entries.joinToString("\n") { "${it.value}|${it.key}" })
        Store.put("failSince", failSince.entries.joinToString("\n") { "${it.value}|${it.key}" })
        Store.put("blocked", blocked.joinToString("\n"))
    }

    private fun loadTimes(key: String): Map<String, Long> = Store.get(key).orEmpty().lineSequence().mapNotNull { line ->
        val t = line.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
        line.substringAfter('|') to t
    }.toMap()

    /** Linkleri dener; sonuçlar kaynak geçmişine ve durumlara yazılır. */
    private suspend fun probeAll(list: List<Channel>, parallel: Int): List<Pair<Channel, ScanResult>> {
        val gate = Semaphore(parallel)
        val probed = coroutineScope {
            list.map { c -> async { gate.withPermit { c to Scanner.probe(c) } } }.awaitAll()
        }
        stats = stats + probed.associate { (c, r) -> c.id to Quality.record(stats[c.id], r.ok, if (r.ok) r.ms else 0, r.height) }
        Store.put("stats", Quality.encode(stats))
        recordResults(
            probed.associate { (c, r) -> c.id to r.ok },
            probed = true,
            blockedIds = probed.filter { it.second.blocked }.map { it.first.id }.toSet(),
        )
        return probed
    }

    /** Oynayan link (uygulama bildirir): arka plan kontrolü aynı paneli yorup izleneni kesmesin diye. */
    var playingUrl: String? = null

    private fun disturbsPlaying(c: Channel): Boolean {
        val p = playingUrl ?: return false
        return Scanner.host(c.url) == Scanner.host(p) && (Scanner.isPanel(c.url) || Scanner.isPanel(p))
    }

    /**
     * Arka plan kontrolü: elle taramaya gerek kalmasın diye linkler azar azar, sırası gelince yeniden denenir.
     * Önce hiç denenmemişler, sonra açılmayanlar (1 saatte bir), sonra çalışanlar (12 saatte bir).
     */
    private fun dueLinks(limit: Int): List<Channel> {
        val now = nowMs()
        fun age(c: Channel) = now - (checked[c.id] ?: 0L)
        return channels.asSequence().distinctBy { it.id }
            .filter { !Backups.isVod(it.url) && !disturbsPlaying(it) }
            .mapNotNull { c ->
                val a = age(c)
                when {
                    c.id !in checked -> 0 to c
                    c.id in failSince && a > AUTO_RETRY_BROKEN_MS -> 1 to c
                    a > AUTO_RETRY_OK_MS -> 2 to c
                    else -> null
                }
            }
            .sortedWith(compareBy({ it.first }, { -age(it.second) }))
            .take(limit).map { it.second }.toList()
    }

    private fun startAutoCheck() {
        scope.launch {
            delay(60_000) // açılışta oynatıcıyla bant genişliği yarışmasın
            while (true) {
                if (scan == null && channels.isNotEmpty()) {
                    val batch = dueLinks(AUTO_BATCH)
                    if (batch.isNotEmpty()) probeAll(batch, 3)
                    reportDead()
                }
                delay(AUTO_INTERVAL_MS)
            }
        }
    }

    /** 3 günden uzun süredir açılmayan (engelli olmayan, o süre içinde yeniden denenmiş) linkler. */
    private fun longDead(): Map<String, Long> {
        val now = nowMs()
        return failSince.filter { (id, since) ->
            id !in blocked && now - since > DEAD_AFTER_MS && (checked[id] ?: 0L) - since > AUTO_RETRY_BROKEN_MS
        }
    }

    private var deadReported: Set<String>? = null

    /**
     * Uzun süredir ölü linkleri Gist'teki olu.txt'ye yazar; gist_guncelle.py bunları bilgisayardan yeniden dener,
     * hâlâ açılmıyorsa Gist'e yüklemez. Listede artık olmayan (zaten çıkarılmış) eski kayıtlar korunur.
     */
    private suspend fun reportDead() {
        val id = Gist.idFrom(url) ?: return
        if (token.isBlank()) return
        val dead = longDead()
        if (dead.keys == deadReported) return
        try {
            val present = channels.map { it.id }.toSet()
            val old = Gist.readDead(url).filterKeys { it !in present }
            Gist.writeDead(id, token, old + dead)
            deadReported = dead.keys
        } catch (e: Throwable) {
            // Bir sonraki turda yeniden denenir
        }
    }

    fun setMerge(on: Boolean) {
        options.mergeDuplicates = on
        Store.put("merge", if (on) "1" else "0")
        current = current?.let(::repOf)
    }

    // Birleşik listede gizlenen kopyalar (kümelerin ilki dışındakiler); liste ya da yayın akışı değişince yeniden hesaplanır
    private var hiddenCopies = emptySet<String>()
    private var hiddenFor: Pair<Any?, Any?>? = null

    private fun copies(): Set<String> {
        val key = backupClusters to epg
        if (hiddenFor !== null && hiddenFor!!.first === key.first && hiddenFor!!.second === key.second) return hiddenCopies
        hiddenCopies = backupClusters.values.flatMap { c ->
            val valid = clusterOf(c.first())
            if (valid.size > 1) valid.drop(1).map { it.id } else emptyList()
        }.toSet()
        hiddenFor = key
        return hiddenCopies
    }

    private fun merged(list: List<Channel>) =
        if (options.mergeDuplicates) copies().let { hide -> list.filter { it.id !in hide } } else list

    /** Önceden yüklenecek komşular: kanal geçişinde sıradaki ve önceki kanal. */
    fun neighbors(): List<Channel> = listOfNotNull(neighbor(1), neighbor(-1)).distinct().filter { it != current }

    private fun neighbor(step: Int): Channel? {
        val list = inGroup(group)
        if (list.size < 2) return null
        val i = list.indexOf(current).coerceAtLeast(0)
        return (1 until list.size).asSequence()
            .map { list[(i + it * step).mod(list.size)] }
            // Birleşik kanalda yalnızca hiçbir kaynağı açılmıyorsa atlanır (ilk kaynağın bozuk olması yetmez)
            .firstOrNull { !isDead(it) }
    }

    fun start() {
        scope.launch {
            // Önce önbellekteki liste: uygulama ağ beklemeden anında açılır
            val cached = withContext(Dispatchers.Default) { Store.readCache()?.let(M3u::parse) }
            if (!cached.isNullOrEmpty()) apply(cached)
            refresh()
            loadEpg()
            pullSettings()
            pullEdits()
        }
        startAutoCheck()
    }

    /** Gist'teki sıralama daha yeniyse onu alır; bu cihazdaki daha yeniyse Gist'e gönderir. */
    fun pullSettings(onDone: ((String) -> Unit)? = null) {
        scope.launch {
            val text = try {
                Gist.readSettings(url, token)
            } catch (e: Throwable) {
                null
            }
            val remote = text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            val remoteUpdated = remote?.get("updated")?.jsonPrimitive?.longOrNull ?: 0L
            when {
                remote != null && remoteUpdated > syncUpdated -> {
                    order = remote.urls("order").map(::linkId)
                    Store.put("order", order.joinToString("\n"))
                    setSynced(remoteUpdated)
                    apply(all)
                    onDone?.invoke("Sıralama Gist'ten alındı")
                }
                // Gist'te henüz kayıt yok ama bu cihazda sıralama var: ilk kez gönder
                remote == null && order.isNotEmpty() -> {
                    if (syncUpdated == 0L) setSynced(nowMs())
                    pushSettings(onDone)
                }
                syncUpdated > remoteUpdated -> pushSettings(onDone)
                else -> onDone?.invoke(if (text == null) "Gist'te kayıtlı ayar yok" else "Sıralama güncel")
            }
        }
    }

    /** Sıralama değişti: kısa bir beklemeden sonra Gist'e gönderilir (art arda değişiklikler tek seferde). */
    private fun settingsChanged() {
        setSynced(nowMs())
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(3_000)
            pushSettings(null)
        }
    }

    private suspend fun pushSettings(onDone: ((String) -> Unit)?) {
        val id = Gist.idFrom(url)
        if (id == null || token.isBlank()) {
            onDone?.invoke("Eşitleme için ayarlara GitHub token ekle")
            return
        }
        val json = buildJsonObject {
            put("updated", syncUpdated)
            putJsonArray("order") { order.forEach { add(it) } }
        }
        try {
            Gist.writeSettings(id, token, json.toString())
            onDone?.invoke("Sıralama Gist'e kaydedildi")
        } catch (e: Throwable) {
            onDone?.invoke("Eşitlenemedi: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun setSynced(time: Long) {
        syncUpdated = time
        Store.put("syncUpdated", time.toString())
    }

    private fun JsonObject.urls(name: String) =
        (this[name] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    fun loadEpg() {
        val src = epgUrl.trim()
        if (src.isEmpty()) {
            epg = null
            return
        }
        scope.launch {
            epg = try {
                withContext(Dispatchers.Default) {
                    val bytes = httpGetBytes(src)
                    // gzip imzası 1f 8b: adres .gz olmasa da içerik sıkıştırılmış olabilir
                    val xml = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) gunzip(bytes) else bytes
                    val now = nowMs()
                    Epg.parse(xml.decodeToString(), now - 3 * 3600_000L, now + 36 * 3600_000L)
                }
            } catch (e: Throwable) {
                null
            }
        }
    }

    /** Kaynak taraması sürerken (bitti, toplam); taranmıyorsa null. */
    var scan by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    private var scanJob: Job? = null

    /**
     * Tüm linkleri dener: açılıyor mu, ne kadar hızlı, hangi çözünürlükte. Sonuçlar kaynak geçmişine yazılır;
     * böylece her kanal kendiliğinden en hızlı kaynağıyla açılır. Aynı anda en çok [SCAN_PARALLEL] link, aynı sunucuya
     * en çok [SCAN_PER_HOST] bağlantı: tek sunucuda binlerce link olan panel taramanın sonunu tek başına uzatmasın.
     */
    fun scanSources(onDone: (String) -> Unit) {
        if (scanJob?.isActive == true) return
        val links = channels.distinctBy { it.id }.filterNot { Backups.isVod(it.url) }
        if (links.isEmpty()) return
        val reps = channels.map(::repOf).distinctBy { it.id }.filter { clusterOf(it).size > 1 }
        val before = reps.associate { it.id to sourcesFor(it).firstOrNull()?.id }
        scan = 0 to links.size
        scanJob = scope.launch {
            var done = 0
            var okCount = 0
            // Sonuçlar biriktirilip yarım saniyede bir yazılır: her linkte binlerce girdilik haritayı kopyalayıp
            // arayüzü yeniden çizdirmek taramayı ilerledikçe yavaşlatıyordu
            val newStats = HashMap<String, SourceStat>()
            val results = HashMap<String, Boolean>()
            val blockedNow = HashSet<String>()
            fun flush() {
                if (results.isEmpty()) return
                stats = stats + newStats
                recordResults(results, probed = true, blockedIds = blockedNow.toSet())
                newStats.clear()
                results.clear()
                blockedNow.clear()
                scan = done to links.size
            }
            val gate = Semaphore(SCAN_PARALLEL)
            val hosts = HashMap<String, Semaphore>()
            val ticker = launch {
                while (true) {
                    delay(500)
                    flush()
                }
            }
            try {
                links.map { ch ->
                    val hostGate = hosts.getOrPut(Scanner.host(ch.url)) { Semaphore(SCAN_PER_HOST) }
                    launch {
                        // Önce sunucu sırası: sırası gelmeyen link genel kotayı boşuna tutmasın
                        hostGate.withPermit {
                            gate.withPermit {
                                val r = Scanner.probe(ch)
                                newStats[ch.id] = Quality.record(stats[ch.id], r.ok, if (r.ok) r.ms else 0, r.height)
                                results[ch.id] = r.ok
                                if (r.blocked) blockedNow += ch.id
                                if (r.ok) okCount++
                                done++
                            }
                        }
                    }
                }.joinAll()
                ticker.cancel()
                flush()
                val changed = reps.count { sourcesFor(it).firstOrNull()?.id != before[it.id] }
                onDone("Tarama bitti · ${okCount}/${links.size} link açıldı · $changed kanalda daha iyi kaynak seçildi")
            } finally {
                ticker.cancel()
                flush()
                Store.put("stats", Quality.encode(stats))
                scan = null
            }
        }
    }

    /**
     * Kanal açıldıktan sonra bir kez çağrılır: kanalın diğer kaynaklarını arka planda dener, durumlarını günceller ve
     * [playing]'den belirgin şekilde daha yüksek çözünürlükte açılan en iyisini döndürür; yoksa null.
     * Kullanıcının sabitlediği kaynak varsa bakılmaz.
     */
    suspend fun findBetter(ch: Channel, playing: Channel): Channel? {
        if (pins[repOf(ch).id] != null) return null
        val candidates = sourcesFor(ch).filter {
            it.id != playing.id && it.id !in avoided && !Backups.isVod(it.url) &&
                // Aynı paneldeki ikinci bağlantı izlenen yayını kesebilir
                !(Scanner.host(it.url) == Scanner.host(playing.url) && (Scanner.isPanel(it.url) || Scanner.isPanel(playing.url)))
        }
        if (candidates.isEmpty()) return null
        val probed = probeAll(candidates, 3)
        val have = Quality.tier("", stats[playing.id]?.height ?: 0)
        return probed.filter { (_, r) -> r.ok }.map { it.first }
            .filter { Quality.tier("", stats[it.id]?.height ?: 0) > have }
            .maxWithOrNull(compareBy<Channel>({ stats[it.id]?.height ?: 0 }, ::openScore))
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    /** "Açılmadı" işareti [BROKEN_TTL_MS] sonra düşer: kalıcı kalıp kanalı sonsuza dek atlatmasın, yeniden denensin. */
    fun isBroken(ch: Channel) = broken[ch.id]?.let { nowMs() - it < BROKEN_TTL_MS } == true

    /** Listede "açılmadı": birleşik kanalda yalnızca hiçbir kaynağı açılmıyorsa. */
    fun isDead(ch: Channel) = members(ch).all(::isBroken)

    fun markBroken(ch: Channel, isBroken: Boolean) {
        if (isBroken == (ch.id in broken)) return
        recordResults(mapOf(ch.id to !isBroken))
    }

    private fun loadBroken(): Map<String, Long> {
        val limit = nowMs() - BROKEN_TTL_MS
        return Store.get("broken").orEmpty().lineSequence().mapNotNull { line ->
            val t = line.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            if (t < limit) null else linkId(line.substringAfter('|')) to t
        }.toMap()
    }

    /** Ada göre arama; Türkçe harf ve büyük/küçük harf farkı gözetmez. */
    fun search(query: String): List<Channel> {
        val q = norm(query).trim()
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ').filter { it.isNotEmpty() }
        return merged(channels.filter { ch -> norm(ch.name).let { n -> words.all { it in n } } })
    }

    private fun norm(s: String) = s.lowercase()
        .replace("i̇", "i").replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')

    fun playPrevious(): Boolean {
        val p = previous?.let { prev -> channels.firstOrNull { it.id == prev.id } } ?: return false
        play(p, previousGroup)
        return true
    }

    /** [onDone], sonucu kullanıcıya gösterilecek kısa bir metinle çağrılır. */
    fun refresh(onDone: ((String) -> Unit)? = null) {
        if (loading) return
        scope.launch {
            loading = true
            error = null
            try {
                val (text, list) = withContext(Dispatchers.Default) {
                    val t = httpGet(url)
                    t to M3u.parse(t)
                }
                if (list.isEmpty()) {
                    error = "Listede kanal bulunamadı"
                    onDone?.invoke("Listede kanal bulunamadı")
                } else {
                    apply(list)
                    withContext(Dispatchers.Default) { Store.writeCache(text) }
                    onDone?.invoke("Liste güncellendi · ${list.size} kanal")
                }
            } catch (e: Throwable) {
                val msg = "Liste indirilemedi: ${e.message ?: e::class.simpleName}"
                if (channels.isEmpty()) error = msg
                onDone?.invoke(msg)
            } finally {
                loading = false
            }
        }
    }

    fun saveSettings(newUrl: String, newToken: String, newEpgUrl: String) {
        val tokenChanged = newToken.trim() != token
        token = newToken.trim()
        Store.put("ghToken", token)
        showSetup = false
        if (tokenChanged && token.isNotBlank()) pullSettings()
        if (newEpgUrl.trim() != epgUrl) {
            epgUrl = newEpgUrl.trim()
            Store.put("epgUrl", epgUrl)
            loadEpg()
        }
        if (newUrl.trim() != url) {
            url = newUrl.trim()
            Store.put("url", url)
            refresh()
        }
    }

    fun deleteChannel(ch: Channel, onDone: (String) -> Unit) {
        // Silinen kanal izleniyorsa aynı kategoride sıradakine geç
        if (ch == current) zap(1)
        hidden = hidden + ch.id
        Store.put("hidden", hidden.joinToString("\n"))
        apply(all)

        val id = Gist.idFrom(url)
        when {
            id == null -> onDone("${ch.name} silindi (liste Gist değil, yalnızca bu cihazdan)")
            token.isBlank() -> onDone("${ch.name} bu cihazdan silindi · Gist'ten de silmek için ayarlara GitHub token ekle")
            else -> scope.launch {
                onDone("${ch.name} Gist'ten siliniyor…")
                try {
                    Gist.deleteChannel(id, token, ch.url)
                    onDone("${ch.name} silindi · Gist güncellendi")
                } catch (e: Throwable) {
                    onDone("${ch.name} bu cihazdan silindi; Gist: ${e.message ?: e::class.simpleName}")
                }
            }
        }
    }

    fun restoreDeleted(onDone: (String) -> Unit) {
        val count = hidden.size
        hidden = emptySet()
        Store.put("hidden", "")
        apply(all)
        val id = Gist.idFrom(url)
        if (id == null || token.isBlank()) {
            onDone("$count kanal geri getirildi")
            return
        }
        scope.launch {
            try {
                val back = Gist.restoreAll(id, token)
                onDone("Silinenler geri getirildi · Gist'e $back kanal geri eklendi")
                refresh()
            } catch (e: Throwable) {
                onDone("Bu cihazda geri getirildi; Gist: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** Kanalı, [shown] listesindeki komşusuyla yer değiştirir; başarılıysa true. */
    fun move(ch: Channel, shown: List<Channel>, dir: Int): Boolean {
        val other = shown.getOrNull(shown.indexOf(ch) + dir) ?: return false
        val list = all.toMutableList()
        val a = list.indexOf(ch)
        val b = list.indexOf(other)
        if (a < 0 || b < 0) return false
        list[a] = other
        list[b] = ch
        saveOrder(list.map { it.id })
        apply(list)
        return true
    }

    fun resetOrder() {
        saveOrder(emptyList())
        refresh()
    }

    private fun saveOrder(urls: List<String>) {
        order = urls
        Store.put("order", urls.joinToString("\n"))
        settingsChanged()
    }

    fun inGroup(g: String): List<Channel> = merged(byGroup[g] ?: channels)

    fun play(ch: Channel, inGroup: String) {
        // Tarama sürerken oynatıcı kapalı: kanal değişse de açılmaz
        if (scan != null) return
        current?.takeIf { it != ch }?.let {
            previous = it
            previousGroup = group
        }
        current = ch
        group = inGroup
        Store.put("last", ch.id)
        Store.put("group", inGroup)
    }

    /** Sıradaki/önceki kanala geçer; açılamadığı bilinen kanallar atlanır (hepsi bozuksa atlanmaz). */
    fun zap(step: Int) {
        if (scan != null) return
        val list = inGroup(group)
        if (list.isEmpty()) return
        val i = list.indexOf(current).coerceAtLeast(0)
        play(neighbor(step) ?: list[(i + step).mod(list.size)], group)
    }

    /** Kanalın adını/kategorisini/logosunu değiştirir: önce bu cihazda, token varsa Gist'teki listede de. */
    fun editChannel(ch: Channel, e: ChannelEdit, onDone: (String) -> Unit) {
        val old = edits[ch.id]
        edits = edits + (ch.id to ChannelEdit(e.name ?: old?.name, e.group ?: old?.group, e.logo ?: old?.logo))
        saveEdits()
        apply(all)
        val id = Gist.idFrom(url)
        if (id == null || token.isBlank()) {
            onDone("Kaydedildi (yalnızca bu cihazda) · Gist için ayarlara token ekle")
            return
        }
        scope.launch {
            onDone("Kaydediliyor…")
            try {
                Gist.saveEdit(id, token, ch.id, e)
                onDone("Kaydedildi · Gist güncellendi")
            } catch (err: Throwable) {
                onDone("Bu cihazda kaydedildi; Gist: ${err.message ?: err::class.simpleName}")
            }
        }
    }

    /** Başka cihazlarda yapılan düzenlemeleri Gist'ten alır. */
    private fun pullEdits() {
        scope.launch {
            val remote = runCatching { Gist.readEdits(url) }.getOrDefault(emptyMap())
            if (remote.isEmpty()) return@launch
            edits = edits + remote
            saveEdits()
            apply(all)
        }
    }

    private fun saveEdits() {
        val json = buildJsonObject {
            edits.forEach { (id, e) ->
                putJsonObject(id) {
                    e.name?.let { put("name", it) }
                    e.group?.let { put("group", it) }
                    e.logo?.let { put("logo", it) }
                }
            }
        }
        Store.put("edits", json.toString())
    }

    private fun loadEdits(): Map<String, ChannelEdit> {
        val obj = Store.get("edits")?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return emptyMap()
        return obj.mapValues { (_, v) ->
            val o = v.jsonObject
            ChannelEdit(o["name"]?.jsonPrimitive?.content, o["group"]?.jsonPrimitive?.content, o["logo"]?.jsonPrimitive?.content)
        }
    }

    /** Var olan kategoriler (düzenleme ekranındaki seçenekler için). */
    fun realGroups() = groups.filter { it != ALL }

    private fun apply(raw: List<Channel>) {
        // Düzenlemeler uygulanır (tekrar uygulanması zararsız); boş logo = logoyu kaldır
        val source = raw.map { ch ->
            val e = edits[ch.id] ?: return@map ch
            ch.copy(
                name = e.name?.ifBlank { null } ?: ch.name,
                group = e.group?.ifBlank { null } ?: ch.group,
                logo = if (e.logo == null) ch.logo else e.logo.ifBlank { null },
            )
        }
        // Kullanıcı sırası; sıralamada olmayan (yeni) kanallar sona, kendi sıralarıyla eklenir
        val pos = order.withIndex().associate { it.value to it.index }
        all = if (pos.isEmpty()) source else source.withIndex()
            .sortedBy { (i, ch) -> pos[ch.id] ?: (pos.size + i) }
            .map { it.value }
        val list = all.filter { it.id !in hidden }
        channels = list
        backupClusters = Backups.clusters(list)
        byGroup = list.groupBy { it.group } + (ALL to list)
        listGroups = list.map { it.group }.distinct()
        if (group !in groups) group = Store.get("group")?.takeIf { it in groups } ?: ALL
        val keep = current?.id ?: Store.get("last")?.let(::linkId)
        current = (list.firstOrNull { it.id == keep } ?: list.firstOrNull())?.let(::repOf)
    }
}
