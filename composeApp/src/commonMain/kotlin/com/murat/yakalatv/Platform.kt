package com.murat.yakalatv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import coil3.Uri
import coil3.fetch.Fetcher

enum class PlayerStatus { Loading, Playing, Error }

/** Görüntünün ekrana yerleşimi. */
enum class Aspect(val label: String) { Fit("Sığdır"), Zoom("Doldur (kırp)"), Stretch("Uzat") }

/** Oynatıcı ile arayüz arasındaki ayarlar: görüntü oranı ve ses dili. */
class PlayerOptions {
    var aspect by mutableStateOf(Store.get("aspect")?.let { a -> Aspect.entries.firstOrNull { it.name == a } } ?: Aspect.Fit)
    /** Yayındaki ses parçalarının adları (oynatıcı doldurur). */
    var audioTracks by mutableStateOf(emptyList<String>())
    /** Seçili ses parçası; -1 = yayının varsayılanı. */
    var audioIndex by mutableIntStateOf(-1)
    /** Kanal açılmazsa yedek linklerine kendiliğinden geçilsin mi? */
    // Açılmayan kaynakta her zaman sessizce sıradakine geçilir
    var autoBackup by mutableStateOf(true)
    /** Oynayan link: 0 = seçilen kanalın kendisi, 1+ = yedekleri (oynatıcı doldurur). */
    var source by mutableIntStateOf(0)
    /** Aynı kanalın kopyaları listede tek satırda birleşsin mi? */
    var mergeDuplicates by mutableStateOf(Store.get("merge") != "0")
    /** Oynatıcı bir linkin sonucunu bildirir: link, açıldı mı, açılış süresi (ms), görüntü yüksekliği (0 = bilinmiyor). */
    var onResult: (url: String, ok: Boolean, openMs: Long, height: Int) -> Unit = { _, _, _, _ -> }
}

/** Bloklayan HTTP GET; arka plan iş parçacığında çağrılmalı. */
expect fun httpGet(url: String): String

/** Bloklayan HTTP GET (ham bayt); arka plan iş parçacığında çağrılmalı. */
expect fun httpGetBytes(url: String): ByteArray

/** .gz verisini açar. */
expect fun gunzip(data: ByteArray): ByteArray

/** Epoch milisaniyesini cihaz saatine göre "SS:dd" yazar. */
expect fun formatClock(epochMs: Long): String

/** Logolar için platformun ağ indiricisi. */
expect fun logoFetcher(): Fetcher.Factory<Uri>

class HttpResult(val code: Int, val body: String)

/** Tarama isteğinin sonucu: yanıt geldi mi; metin listesiyse (#EXTM3U) içeriği; HTTP durum kodu (0 = yanıt yok). */
class ProbeResult(val ok: Boolean, val text: String?, val code: Int = 0)

/** Bir parçası "#" ile başlıyorsa metin say (HLS listesi), değilse yayın baytı. */
fun probeText(buf: ByteArray, n: Int): String? = if (n > 0 && buf[0] == '#'.code.toByte()) buf.decodeToString(0, n) else null

/** Linkin ilk [maxBytes] baytını indirip bağlantıyı kapatır (sonsuz .ts akışları için). */
expect suspend fun probeStream(url: String, headers: Map<String, String>, maxBytes: Int, timeoutMs: Long): ProbeResult

/** Başlık ve gövdeli istek (GitHub API için GET/PATCH). */
expect suspend fun httpSend(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult

expect object Store {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun readCache(): String?
    fun writeCache(text: String)
}

/**
 * Tek bir oynatıcı örneği tutar; kanal değişince yalnızca kaynağı değiştirir (hızlı geçiş için).
 * [sources]: önce kanalın kendisi, sonra açılmazsa denenecek yedekleri.
 * [preload]: arka planda önceden yüklenecek komşu kanallar (anında geçiş için).
 */
@Composable
expect fun VideoPlayer(
    sources: List<Channel>,
    preload: List<Channel>,
    options: PlayerOptions,
    onStatus: (PlayerStatus) -> Unit,
    modifier: Modifier,
)

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Tuş basılı tutulduğu için gelen tekrar olayı mı? */
expect fun KeyEvent.isRepeat(): Boolean

/** Uygulamadan çıkar (iOS'ta uygulamalar kendini kapatmaz; orada bir şey yapmaz). */
expect fun exitApp()
