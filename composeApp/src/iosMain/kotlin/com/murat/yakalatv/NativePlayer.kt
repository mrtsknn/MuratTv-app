package com.murat.yakalatv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.UIKit.UIView

/**
 * Swift'te yazılan oynatıcı (VLC). Kotlin VLC'yi doğrudan görmez; Swift bu arayüzü uygular
 * ve uygulama açılırken [nativePlayerFactory]'yi doldurur.
 */
interface NativePlayer {
    val view: UIView

    /** 0 = açılıyor, 1 = oynuyor, 2 = hata / yayın bitti. */
    val state: Int

    fun play(url: String, userAgent: String?, referrer: String?)
    fun stop()

    /** 0 = sığdır, 1 = doldur (kırp), 2 = uzat. */
    fun setAspect(mode: Int)
}

/** Swift tarafından iOSApp içinde, MainViewController()'dan önce atanır. */
var nativePlayerFactory: (() -> NativePlayer)? = null

@Composable
internal fun NativeVideoPlayer(
    factory: () -> NativePlayer,
    sources: List<Channel>,
    options: PlayerOptions,
    onStatus: (PlayerStatus) -> Unit,
    modifier: Modifier,
) {
    val status = rememberUpdatedState(onStatus)
    val srcs = rememberUpdatedState(sources)
    val player = remember { factory() }

    DisposableEffect(Unit) { onDispose { player.stop() } }

    LaunchedEffect(sources.firstOrNull()?.url) {
        var i = 0
        while (isActive) {
            val ch = srcs.value.getOrNull(i) ?: return@LaunchedEffect status.value(PlayerStatus.Error)
            options.source = i
            player.play(ch.url, ch.userAgent, ch.referrer)
            // 10 sn'de açılmazsa ya da hata verirse sıradaki link denenir
            var waited = 0
            var opened = false
            while (isActive) {
                val s = player.state
                if (s == 1 && !opened) {
                    opened = true
                    options.onResult(ch.url, true, waited.toLong(), 0)
                }
                if (s == 2 || (!opened && waited >= 10_000)) {
                    if (!opened) options.onResult(ch.url, false, 0, 0)
                    break
                }
                status.value(if (opened) PlayerStatus.Playing else PlayerStatus.Loading)
                delay(500)
                waited += 500
            }
            if (!options.autoBackup) {
                player.stop()
                return@LaunchedEffect status.value(PlayerStatus.Error)
            }
            i++
        }
    }

    // Ses dili seçimi iOS'ta henüz yok; görüntü oranı uygulanır
    LaunchedEffect(options.aspect) {
        player.setAspect(
            when (options.aspect) {
                Aspect.Fit -> 0
                Aspect.Zoom -> 1
                Aspect.Stretch -> 2
            }
        )
    }

    UIKitView(factory = { player.view }, modifier = modifier)
}
