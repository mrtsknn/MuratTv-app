package com.murat.yakalatv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import coil3.Uri
import coil3.fetch.Fetcher
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

@SuppressLint("StaticFieldLeak")
lateinit var appContext: Context

actual fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 20_000
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("Cache-Control", "no-cache")
    try {
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

actual fun httpGetBytes(url: String): ByteArray {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 30_000
    conn.instanceFollowRedirects = true
    try {
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        return conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
}

actual fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }

actual fun formatClock(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

actual fun logoFetcher(): Fetcher.Factory<Uri> = OkHttpNetworkFetcherFactory(callFactory = { okHttp })

private val okHttp by lazy { OkHttpClient() }

actual suspend fun probeStream(url: String, headers: Map<String, String>, maxBytes: Int, timeoutMs: Long): ProbeResult =
    withContext(Dispatchers.IO) {
        val client = okHttp.newBuilder().callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val req = okhttp3.Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        try {
            client.newCall(req).execute().use { r ->
                val input = r.body?.byteStream()
                if (!r.isSuccessful || input == null) return@use ProbeResult(false, null, r.code)
                val buf = ByteArray(maxBytes)
                var n = 0
                while (n < maxBytes) {
                    val k = input.read(buf, n, maxBytes - n)
                    if (k < 0) break
                    n += k
                }
                ProbeResult(n > 0, probeText(buf, n), r.code)
            }
        } catch (e: Exception) {
            ProbeResult(false, null)
        }
    }

actual suspend fun httpSend(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult =
    withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        okHttp.newCall(req).execute().use { HttpResult(it.code, it.body?.string().orEmpty()) }
    }

actual object Store {
    private val prefs by lazy { appContext.getSharedPreferences("yakala", Context.MODE_PRIVATE) }
    private val cacheFile by lazy { File(appContext.filesDir, "playlist.m3u") }

    actual fun get(key: String): String? = prefs.getString(key, null)
    actual fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    actual fun readCache(): String? = cacheFile.takeIf { it.exists() }?.readText()
    actual fun writeCache(text: String) {
        val tmp = File(cacheFile.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(cacheFile)
    }
}

/** Bir link bu sürede açılmazsa sıradaki (yedek) denenir. */
private const val SOURCE_TIMEOUT_MS = 10_000L
/** Komşu kanalların önceden yüklenecek süresi. */
private const val PRELOAD_MS = 2_000L
/** Bu süreden eski önceden yüklemeler yenilenir (canlı yayın bayatlamasın). */
private const val PRELOAD_FRESH_MS = 60_000L

/**
 * Tek ExoPlayer ve komşu kanalları önceden yükleyen yönetici. Kanalı sırayla dener:
 * kendi linki (gerekirse HLS olarak tekrar), sonra yedekleri.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class PlayerController(context: Context, private val scope: CoroutineScope, private val options: PlayerOptions) {
    var sources: List<Channel> = emptyList()
    var onStatus: (PlayerStatus) -> Unit = {}

    private var index = 0
    private var forcedHls = false
    private var fromPreload = false
    private var ready = false
    private var startedAt = 0L
    private var timeout: Job? = null
    private val audioGroups = ArrayList<Tracks.Group>()
    /** Önceden yüklenen kanallar: link → (öğe, eklenme zamanı). */
    private val preloaded = HashMap<String, Pair<MediaItem, Long>>()
    /** Oynatıcıya verilmiş önceden yüklenmiş öğeler; oynatıcı bırakınca yöneticiden silinir. */
    private val handedOver = ArrayList<MediaItem>()
    private val preloader: DefaultPreloadManager
    val player: ExoPlayer

    init {
        // Emülatörün "goldfish" donanım çözücüsü yeşil/mor bozuk görüntü verir; varsa sona alınır
        val codecs = MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).sortedBy { "goldfish" in it.name }
        }
        val builder = DefaultPreloadManager.Builder(
            context,
            TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> { rank ->
                if (rank > 0) {
                    DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(PRELOAD_MS)
                } else {
                    DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
                }
            },
        )
            .setRenderersFactory(DefaultRenderersFactory(context).setMediaCodecSelector(codecs).setEnableDecoderFallback(true))
            // Canlı yayında hızlı açılış: 0,7 sn tampon dolunca oynatmaya başla
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 30_000, 700, 1_500).build())
        preloader = builder.build()
        player = builder.buildExoPlayer().apply { playWhenReady = true }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> onStatus(PlayerStatus.Loading)
                    Player.STATE_READY -> onReady()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                } else {
                    fail()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // Görüntü boyutu ilk kareden sonra belli olur; kalite bilgisi güncellenir (-2 = yalnızca yükseklik)
                val ch = sources.getOrNull(index)
                if (ch != null && videoSize.height > 0) options.onResult(ch.url, true, -2, videoSize.height)
            }

            override fun onTracksChanged(tracks: Tracks) {
                audioGroups.clear()
                audioGroups += tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
                options.audioTracks = audioGroups.mapIndexed { i, g -> audioLabel(g.getTrackFormat(0), i) }
            }
        })
    }

    fun play(newSources: List<Channel>) {
        sources = newSources
        options.audioIndex = -1
        options.audioTracks = emptyList()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        start(0, forceHls = false, usePreload = true)
    }

    private fun start(i: Int, forceHls: Boolean, usePreload: Boolean) {
        val ch = sources.getOrNull(i) ?: return onStatus(PlayerStatus.Error)
        index = i
        forcedHls = forceHls
        ready = false
        options.source = i
        val warm = if (usePreload && !forceHls) {
            preloaded.remove(ch.url)?.first?.let { item -> preloader.getMediaSource(item)?.also { handedOver += item } }
        } else {
            null
        }
        fromPreload = warm != null
        startedAt = SystemClock.elapsedRealtime()
        player.setMediaSource(warm ?: ch.mediaSource(ch.mediaItem(forceHls)))
        player.prepare()
        timeout?.cancel()
        // Bazı bozuk yayınlar hata vermeden bekler; süre dolunca sıradaki denenir
        timeout = scope.launch {
            delay(SOURCE_TIMEOUT_MS)
            if (!ready) fail()
        }
    }

    private fun onReady() {
        if (!ready) {
            ready = true
            timeout?.cancel()
            val ms = SystemClock.elapsedRealtime() - startedAt
            Log.d("MuratTv", "açıldı: ${sources.getOrNull(index)?.name} $ms ms, önceden yüklü=$fromPreload, kaynak=$index")
            // Önceden yüklenen kaynağın açılış süresi bağlantı hızını göstermez; o durumda süre bildirilmez
            sources.getOrNull(index)?.let { options.onResult(it.url, true, if (fromPreload) -1 else ms, player.videoSize.height) }
            // Yedek olarak canlı yayın yerine bir programın kaydı açıldıysa (süresi belli, canlı değil) o da atlanır
            if (index > 0 && !player.isCurrentMediaItemLive && player.duration != C.TIME_UNSET) {
                ready = false
                return fail()
            }
        }
        onStatus(PlayerStatus.Playing)
    }

    private fun fail() {
        val ch = sources.getOrNull(index)
        when {
            // Önceden yüklenen bağlantı beklerken kopmuş olabilir: önce taze bağlantıyla dene
            fromPreload -> start(index, forcedHls, usePreload = false)
            ch != null && !forcedHls && !ch.looksLikeHls() -> start(index, forceHls = true, usePreload = false)
            options.autoBackup && index + 1 < sources.size -> {
                ch?.let { options.onResult(it.url, false, 0, 0) }
                start(index + 1, forceHls = false, usePreload = true)
            }
            else -> {
                ch?.let { options.onResult(it.url, false, 0, 0) }
                timeout?.cancel()
                onStatus(PlayerStatus.Error)
            }
        }
    }

    fun selectAudio(i: Int) {
        val g = audioGroups.getOrNull(i) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
    }

    fun preload(all: List<Channel>) {
        val now = System.currentTimeMillis()
        // Panel linkleri (.ts) çoğunlukla hesap başına tek bağlantıya izin verir: önceden yüklemek ikinci bağlantı
        // açar ve panel izlenen kanalı da kesebilir. Yalnızca HLS (.m3u8) yayınlar önceden yüklenir.
        val channels = all.filter { it.looksLikeHls() }
        val want = channels.associateBy { it.url }
        // Oynatıcının artık kullanmadığı devredilmiş öğeleri bırak
        val playing = player.currentMediaItem
        handedOver.filter { it != playing }.forEach {
            preloader.remove(it)
            handedOver.remove(it)
        }
        preloaded.entries.filter { it.key !in want || now - it.value.second > PRELOAD_FRESH_MS }.toList().forEach {
            preloader.remove(it.value.first)
            preloaded.remove(it.key)
        }
        for (ch in channels) {
            if (ch.url in preloaded || ch.url == sources.getOrNull(index)?.url) continue
            val item = ch.mediaItem(forceHls = false)
            preloader.add(ch.mediaSource(item), 1)
            preloaded[ch.url] = item to now
        }
        preloader.setCurrentPlayingIndex(0)
        preloader.invalidate()
    }

    fun release() {
        timeout?.cancel()
        player.release()
        preloader.release()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(
    sources: List<Channel>,
    preload: List<Channel>,
    options: PlayerOptions,
    onStatus: (PlayerStatus) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ctl = remember { PlayerController(context, scope, options) }
    ctl.onStatus = onStatus
    DisposableEffect(ctl) { onDispose { ctl.release() } }

    val key = sources.firstOrNull()?.url
    LaunchedEffect(key) { if (key != null) ctl.play(sources) }
    // Yedek listesi sonradan değişebilir (yayın akışı yüklenince); oynayan link değişmeden güncellenir
    SideEffect { if (key != null) ctl.sources = sources }
    LaunchedEffect(options.audioIndex) { ctl.selectAudio(options.audioIndex) }
    // Mevcut kanal açılırken bant genişliği paylaşılmasın diye komşular biraz sonra yüklenir
    LaunchedEffect(preload.map { it.url }, key) {
        delay(1_500)
        ctl.preload(preload)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> ctl.player.stop()
                Lifecycle.Event.ON_START -> if (ctl.player.mediaItemCount > 0) {
                    ctl.player.seekToDefaultPosition()
                    ctl.player.prepare()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(Color.BLACK)
                setBackgroundColor(Color.BLACK)
                // Kaynak değişirken son kare ekranda kalır: kaliteli kaynağa geçiş kararmadan olur
                setKeepContentOnPlayerReset(true)
                keepScreenOn = true
                player = ctl.player
            }
        },
        update = {
            it.resizeMode = when (options.aspect) {
                Aspect.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                Aspect.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                Aspect.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        },
        modifier = modifier,
    )
}

private fun audioLabel(f: Format, i: Int): String {
    val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.forLanguageTag("tr")) }
        ?.replaceFirstChar { it.titlecase(Locale.forLanguageTag("tr")) }
    val name = listOfNotNull(f.label, lang).distinct().joinToString(" · ").ifBlank { "Ses ${i + 1}" }
    return if (f.channelCount >= 6) "$name (5.1)" else name
}

private fun Channel.looksLikeHls() = url.contains(".m3u8", ignoreCase = true)

/** Önceden yükleme ve oynatma aynı öğeyi üretmeli: yönetici öğeleri eşitlikle bulur. */
private fun Channel.mediaItem(forceHls: Boolean): MediaItem = MediaItem.Builder().setUri(url)
    .setMediaId(if (forceHls) "$url#hls" else url)
    .apply { if (forceHls || looksLikeHls()) setMimeType(MimeTypes.APPLICATION_M3U8) }
    .build()

@androidx.annotation.OptIn(UnstableApi::class)
private fun Channel.mediaSource(item: MediaItem): MediaSource {
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(8_000)
        .setReadTimeoutMs(8_000)
        .setUserAgent(userAgent ?: "MuratTv/0.1 Media3")
    referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
    return DefaultMediaSourceFactory(http).createMediaSource(item)
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

actual fun KeyEvent.isRepeat(): Boolean = nativeKeyEvent.repeatCount > 0

/** MainActivity kendini buraya yazar; çıkışta kapatılır. */
var mainActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

actual fun exitApp() {
    mainActivity?.get()?.finish()
}
