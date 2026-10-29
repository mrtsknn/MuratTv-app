@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.murat.yakalatv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.currentItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.timeControlStatus
import platform.CoreGraphics.CGRectMake
import coil3.Uri
import coil3.fetch.Fetcher
import coil3.network.ktor3.KtorNetworkFetcherFactory
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.posix.memcpy
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import kotlinx.coroutines.suspendCancellableCoroutine
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.writeToFile
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

actual fun httpGet(url: String): String {
    val nsUrl = NSURL.URLWithString(url) ?: error("Geçersiz adres")
    return NSString.stringWithContentsOfURL(nsUrl, NSUTF8StringEncoding, null) ?: error("İndirilemedi")
}

@OptIn(ExperimentalForeignApi::class)
actual fun httpGetBytes(url: String): ByteArray {
    val nsUrl = NSURL.URLWithString(url) ?: error("Geçersiz adres")
    val d = NSData.dataWithContentsOfURL(nsUrl) ?: error("İndirilemedi")
    val out = ByteArray(d.length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), d.bytes, d.length) }
    return out
}

@OptIn(ExperimentalForeignApi::class)
actual fun gunzip(data: ByteArray): ByteArray = memScoped {
    val stream = alloc<z_stream>()
    // 15 + 32: zlib gzip başlığını kendisi tanır
    check(inflateInit2_(stream.ptr, 15 + 32, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK) { "gzip açılamadı" }
    val parts = ArrayList<ByteArray>()
    val chunk = ByteArray(64 * 1024)
    try {
        data.usePinned { input ->
            chunk.usePinned { out ->
                stream.next_in = input.addressOf(0).reinterpret()
                stream.avail_in = data.size.convert()
                do {
                    stream.next_out = out.addressOf(0).reinterpret()
                    stream.avail_out = chunk.size.convert()
                    val r = inflate(stream.ptr, Z_NO_FLUSH)
                    check(r == Z_OK || r == Z_STREAM_END) { "gzip açılamadı ($r)" }
                    parts += chunk.copyOf(chunk.size - stream.avail_out.toInt())
                } while (r != Z_STREAM_END)
            }
        }
    } finally {
        inflateEnd(stream.ptr)
    }
    val result = ByteArray(parts.sumOf { it.size })
    var pos = 0
    for (p in parts) {
        p.copyInto(result, pos)
        pos += p.size
    }
    result
}

actual fun formatClock(epochMs: Long): String =
    NSDateFormatter().apply { dateFormat = "HH:mm" }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))

actual fun logoFetcher(): Fetcher.Factory<Uri> = KtorNetworkFetcherFactory()

@OptIn(kotlinx.cinterop.BetaInteropApi::class)
actual suspend fun httpSend(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult =
    suspendCancellableCoroutine { cont ->
        val req = NSMutableURLRequest.requestWithURL(NSURL.URLWithString(url) ?: error("Geçersiz adres"))
        req.setHTTPMethod(method)
        headers.forEach { (k, v) -> req.setValue(v, forHTTPHeaderField = k) }
        body?.let { req.setHTTPBody(NSString.create(string = it).dataUsingEncoding(NSUTF8StringEncoding)) }
        val task = NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, error ->
            if (error != null) {
                cont.resumeWithException(Exception(error.localizedDescription))
            } else {
                val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                val text = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }.orEmpty()
                cont.resume(HttpResult(code, text))
            }
        }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }

private val probeClient by lazy { io.ktor.client.HttpClient(io.ktor.client.engine.darwin.Darwin) }

actual suspend fun probeStream(url: String, headers: Map<String, String>, maxBytes: Int, timeoutMs: Long): ProbeResult =
    try {
        kotlinx.coroutines.withTimeout(timeoutMs) {
            probeClient.prepareGet(url) { headers.forEach { (k, v) -> header(k, v) } }.execute { r ->
                if (r.status.value !in 200..299) return@execute ProbeResult(false, null, r.status.value)
                val ch = r.bodyAsChannel()
                val buf = ByteArray(maxBytes)
                var n = 0
                while (n < maxBytes) {
                    val k = ch.readAvailable(buf, n, maxBytes - n)
                    if (k <= 0) break
                    n += k
                }
                ProbeResult(n > 0, probeText(buf, n), r.status.value)
            }
        }
    } catch (e: Exception) {
        ProbeResult(false, null)
    }

actual object Store {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val cachePath: String by lazy {
        val dir = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first() as NSURL
        dir.path + "/playlist.m3u"
    }

    actual fun get(key: String): String? = defaults.stringForKey(key)
    actual fun put(key: String, value: String) = defaults.setObject(value, key)
    actual fun readCache(): String? = NSString.stringWithContentsOfFile(cachePath, NSUTF8StringEncoding, null)

    @OptIn(kotlinx.cinterop.BetaInteropApi::class)
    actual fun writeCache(text: String) {
        NSString.create(string = text).writeToFile(cachePath, true, NSUTF8StringEncoding, null)
    }
}

private class PlayerHostView(val playerLayer: AVPlayerLayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(playerLayer)
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.setFrame(bounds)
        CATransaction.commit()
    }
}

// iOS'ta önceden yükleme yok; açılmayan kanalda yedeklere geçilir.
// Swift tarafı VLC oynatıcısını kaydettiyse o kullanılır (.ts dahil her şeyi açar), yoksa AVPlayer (yalnızca HLS)
@Composable
actual fun VideoPlayer(
    sources: List<Channel>,
    preload: List<Channel>,
    options: PlayerOptions,
    onStatus: (PlayerStatus) -> Unit,
    modifier: Modifier,
) {
    val factory = nativePlayerFactory
    if (factory != null) {
        NativeVideoPlayer(factory, sources, options, onStatus, modifier)
    } else {
        AvVideoPlayer(sources, options, onStatus, modifier)
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun AvVideoPlayer(
    sources: List<Channel>,
    options: PlayerOptions,
    onStatus: (PlayerStatus) -> Unit,
    modifier: Modifier,
) {
    val status = rememberUpdatedState(onStatus)
    val srcs = rememberUpdatedState(sources)
    val player = remember { AVPlayer() }
    val playerLayer = remember {
        AVPlayerLayer.playerLayerWithPlayer(player).apply { videoGravity = AVLayerVideoGravityResizeAspect }
    }

    DisposableEffect(Unit) { onDispose { player.pause() } }

    LaunchedEffect(sources.firstOrNull()?.url) {
        // AVPlayer ham MPEG-TS (.ts) açamaz, yalnızca HLS. Xtream panelleri aynı yayını .m3u8 olarak da verir:
        // .ts linklerde önce .m3u8 sürümü, olmazsa linkin kendisi denenir
        val attempts = srcs.value.withIndex().flatMap { (i, ch) ->
            val path = ch.url.substringBefore('?')
            if (path.endsWith(".ts", ignoreCase = true)) {
                listOf(i to path.dropLast(3) + ".m3u8" + ch.url.removePrefix(path), i to ch.url)
            } else {
                listOf(i to ch.url)
            }
        }
        var a = 0
        while (isActive) {
            val (i, link) = attempts.getOrNull(a) ?: return@LaunchedEffect status.value(PlayerStatus.Error)
            val ch = srcs.value[i]
            options.source = i
            val url = NSURL.URLWithString(link)
            if (url != null) {
                val headers = buildMap<Any?, Any?> {
                    ch.userAgent?.let { put("User-Agent", it) }
                    ch.referrer?.let { put("Referer", it) }
                }
                val asset = AVURLAsset(uRL = url, options = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers))
                player.replaceCurrentItemWithPlayerItem(AVPlayerItem(asset = asset))
                player.play()
            }
            // Basit durum takibi; KVO yerine yoklama yeterli. 10 sn'de açılmazsa sıradaki link denenir
            var waited = 0
            var opened = false
            while (isActive) {
                val failed = url == null || player.currentItem?.status == AVPlayerItemStatusFailed
                if (player.timeControlStatus == AVPlayerTimeControlStatusPlaying) opened = true
                if (failed || (!opened && waited >= 10_000)) break
                status.value(if (opened) PlayerStatus.Playing else PlayerStatus.Loading)
                delay(500)
                waited += 500
            }
            // Aynı kanalın .m3u8/.ts denemesi otomatik yedek kapalı olsa da yapılır; başka kanala geçiş yalnızca açıkken
            val next = attempts.getOrNull(a + 1)
            if (!options.autoBackup && (next == null || next.first != i)) return@LaunchedEffect status.value(PlayerStatus.Error)
            a++
        }
    }

    // Ses dili seçimi iOS'ta henüz yok; görüntü oranı uygulanır
    LaunchedEffect(options.aspect) {
        playerLayer.videoGravity = when (options.aspect) {
            Aspect.Fit -> AVLayerVideoGravityResizeAspect
            Aspect.Zoom -> AVLayerVideoGravityResizeAspectFill
            Aspect.Stretch -> AVLayerVideoGravityResize
        }
    }

    UIKitView(factory = { PlayerHostView(playerLayer) }, modifier = modifier)
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

actual fun KeyEvent.isRepeat(): Boolean = false

actual fun exitApp() = Unit
