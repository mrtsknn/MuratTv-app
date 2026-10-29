package com.murat.yakalatv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Image
import org.jetbrains.compose.resources.painterResource
import com.murat.yakalatv.res.Res
import com.murat.yakalatv.res.badge_4k
import com.murat.yakalatv.res.badge_fhd
import com.murat.yakalatv.res.badge_hd
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalFocusManager
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.setSingletonImageLoaderFactory
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

// Grafit kırmızı: grafit zemin, seçili satır beyaz, vurgular kırmızı
private val Bg = Color(0xFF0E0E0F)
private val Panel = Color(0xFF1B1B1D)
private val Accent = Color(0xFFE5484D)
private val Sel = Color(0xFFF2F2F2)
private val OnAccent = Color(0xFF141414)
private val Muted = Color(0xFF8C8C90)
private val TextC = Color(0xFFECECEC)
private val Line = Color(0xFF2C2C2F)
private val Glass = Color(0xEE111112)

private const val ACT_REFRESH = "↻  Listeyi yenile"
private const val ACT_SETUP = "Liste adresini değiştir"
private const val ACT_RESTORE = "↺  Silinenleri geri getir"
private const val ACT_RESET_ORDER = "Sıralamayı sıfırla"
private const val ACT_OPTIONS = "Görüntü ve ses"
private const val ACT_SCAN = "⚡  Kaynakları tara"
private const val ACT_EXIT = "Çıkış"
private val LONG_PRESS = 400.milliseconds

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }
    LaunchedEffect(Unit) { state.start() }
    setSingletonImageLoaderFactory { ctx ->
        ImageLoader.Builder(ctx).components { add(logoFetcher()) }.build()
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = OnAccent, background = Bg, surface = Panel)) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            when {
                state.showSetup -> SetupScreen(state)
                state.channels.isNotEmpty() -> PlayerScreen(state)
                state.error != null -> Message(state.error!!, action = "Adresi değiştir") { state.showSetup = true }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun PlayerScreen(s: AppState) {
    var listOpen by remember { mutableStateOf(false) }
    var col by remember { mutableIntStateOf(1) } // 0: kategoriler, 1: kanallar
    var gSel by remember { mutableIntStateOf(0) }
    var cSel by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(PlayerStatus.Loading) }
    var bannerTick by remember { mutableIntStateOf(0) }
    var bannerVisible by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf("") }
    var digitTick by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastTick by remember { mutableIntStateOf(0) }
    // OK'a basılma anı: kısa basış = seç, uzun basış = menü / kaynaklar
    var okDown by remember { mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null) }
    var okLongDone by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<ChannelMenu?>(null) }
    // Düzenlenen kanal (ad, kategori, logo); açıkken tuşlar düzenleme ekranına gider
    var editing by remember { mutableStateOf<Channel?>(null) }
    // Taşıma modu: seçili kanal yukarı/aşağı tuşlarıyla listede yer değiştirir
    var moving by remember { mutableStateOf(false) }
    // Dokunmatik ekranlar (telefon, tablet, iPhone) için tam ekranda görünen düğme çubuğu
    var touchBar by remember { mutableStateOf(false) }
    var touchTick by remember { mutableIntStateOf(0) }
    // Görüntü ve ses paneli
    var optsOpen by remember { mutableStateOf(false) }
    var optSel by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(nowMs()) }
    val focus = remember { FocusRequester() }
    val gState = rememberLazyListState()
    val cState = rememberLazyListState()
    val opts = s.options

    // Kategori sütunu: üstte yenile ve arama, altta ayarlar; imleç bunlardayken komşu kategori gösterilir
    val groupItems = listOf(ACT_REFRESH) + s.groups + listOf(ACT_OPTIONS, ACT_SCAN, ACT_SETUP) +
        listOfNotNull(ACT_RESTORE.takeIf { s.hidden.isNotEmpty() }, ACT_RESET_ORDER.takeIf { s.order.isNotEmpty() }) + ACT_EXIT
    // İlk sekme (MuratTv yazısı) listeyi yeniler; kategoriler 1. sıradan başlar
    val shownGroup = s.groups[(gSel - 1).coerceIn(0, s.groups.lastIndex)]
    val list = s.inGroup(shownGroup)
    val current = s.current
    // Kaynak listesinden elle seçilen link; kanal değişince geçersiz kalır
    var forcedSource by remember { mutableStateOf<String?>(null) }
    // Sıra kanal açılırken bir kez belirlenir: izlerken gelen ölçümler sırayı değiştirip yayını başka kaynağa atlatmasın
    val sources = remember(current?.id, forcedSource, opts.mergeDuplicates, s.channels, s.epg, s.pins, s.avoided) {
        s.sourcesFor(current)
    }.let { list ->
        val f = list.firstOrNull { it.id == forcedSource }
        if (f != null) listOf(f) + (list - f) else list
    }
    val playing = sources.getOrNull(opts.source)
    // Kaynak seçme penceresi: açılanlar, en kaliteliden aşağı. Pencere açılıp kapanınca yeniden sıralanır;
    // açıkken sıra kaymasın diye ölçümler anında yansıtılmaz
    var srcOpen by remember { mutableStateOf(false) }
    val pickerList = remember(current?.id, srcOpen, playing?.id, opts.mergeDuplicates, s.channels, s.epg) {
        if (opts.mergeDuplicates) current?.let { s.pickerSources(it, playing) }.orEmpty() else sources
    }
    LaunchedEffect(playing?.url) { s.playingUrl = playing?.url }
    var srcSel by remember { mutableIntStateOf(0) }
    val srcState = rememberLazyListState()
    // Telefon gibi alçak ekranlarda (iPhone 11 yatay: 414 nokta) daha sıkı düzen; TV'de değişmez
    val compact = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < 520.dp
    val pad = if (compact) 14.dp else 32.dp

    fun openList() {
        gSel = s.groups.indexOf(s.group).coerceAtLeast(0) + 1
        cSel = s.inGroup(s.group).indexOf(current).coerceAtLeast(0)
        col = 1
        listOpen = true
    }

    fun choose() {
        if (col == 0) {
            when (groupItems[gSel]) {
                ACT_REFRESH -> s.refresh { toast = it; toastTick++ }
                ACT_SETUP -> s.showSetup = true
                ACT_RESTORE -> { s.restoreDeleted { toast = it; toastTick++ }; gSel = 1 }
                ACT_RESET_ORDER -> { s.resetOrder(); toast = "Sıralama listedeki haline döndü"; toastTick++; gSel = 1 }
                ACT_OPTIONS -> { optsOpen = true; optSel = 0 }
                ACT_SCAN -> { listOpen = false; s.scanSources { toast = it; toastTick++ } }
                ACT_EXIT -> exitApp()
                else -> { col = 1; cSel = 0 }
            }
            return
        }
        val ch = list.getOrNull(cSel) ?: return
        if (ch == current) listOpen = false else s.play(ch, shownGroup)
    }

    fun lastChannel() {
        if (s.scan != null) {
            toast = "Tarama sürüyor · iptal için ☰ Kanallar"
            toastTick++
        } else if (s.playPrevious()) bannerTick++ else {
            toast = "Önceki kanal yok"
            toastTick++
        }
    }

    fun openSources() {
        srcSel = pickerList.indexOfFirst { it.id == playing?.id }.coerceAtLeast(0)
        srcOpen = true
    }

    // Kaynak penceresi: önce kaynaklar, sonra "varsayılan yap" ve "kapat"
    fun srcChoose(i: Int) {
        val c = pickerList.getOrNull(i)
        when {
            c != null -> {
                forcedSource = c.id
                srcOpen = false
                toast = "Kaynak ${i + 1} açılıyor"
                toastTick++
            }
            i == pickerList.size -> {
                val cur = current
                if (cur != null && playing != null) {
                    s.pinSource(cur, playing)
                    toast = "Kaynak ${pickerList.indexOfFirst { it.id == playing.id } + 1} bu kanal için varsayılan"
                    toastTick++
                }
                srcOpen = false
            }
            i == pickerList.size + 1 -> {
                val cur = current
                if (cur != null && playing != null) {
                    s.toggleAvoid(cur, playing)
                    val k = pickerList.indexOfFirst { it.id == playing.id } + 1
                    if (s.isAvoided(playing)) {
                        // Hemen en iyi diğer kaynağa geç
                        forcedSource = pickerList.filter { it.id != playing.id }.maxByOrNull(s::score)?.id
                        toast = "Kaynak $k artık kendiliğinden seçilmeyecek"
                    } else {
                        toast = "Kaynak $k yeniden kullanılacak"
                    }
                    toastTick++
                }
                srcOpen = false
            }
            else -> srcOpen = false
        }
    }

    // Yayın oynarken sağ/sol: kanalın kaynakları arasında sırayla dolaşır
    fun stepSource(d: Int) {
        val i = pickerList.indexOfFirst { it.id == playing?.id }
        srcChoose((i + d).mod(pickerList.size))
    }

    // Görüntü ve ses paneli: 0 görüntü oranı, 1 ses dili, 2 birleştirme, 3 sıralama eşitleme, 4 kapat
    fun optChoose(i: Int) {
        when (i) {
            0 -> {
                opts.aspect = Aspect.entries[(opts.aspect.ordinal + 1) % Aspect.entries.size]
                Store.put("aspect", opts.aspect.name)
            }
            1 -> if (opts.audioTracks.size > 1) {
                opts.audioIndex = (opts.audioIndex + 1).let { if (it >= opts.audioTracks.size) 0 else it }
            }
            2 -> s.setMerge(!opts.mergeDuplicates)
            3 -> s.pullSettings { toast = it; toastTick++ }
            else -> optsOpen = false
        }
    }

    fun showToast(text: String) {
        toast = text
        toastTick++
    }

    // Tarama sürerken oynatıcı kapalı: kanal değiştirmek yerine nasıl iptal edileceği söylenir
    fun scanBlocks(): Boolean {
        if (s.scan == null) return false
        showToast("Tarama sürüyor · iptal için ☰ Kanallar")
        return true
    }

    fun zap(step: Int) {
        if (scanBlocks()) return
        s.zap(step)
        bannerTick++
    }

    fun openMenu() {
        menu = ChannelMenu(list.getOrNull(cSel) ?: return)
    }

    // OK basılı tutulunca: listede kanal menüsü, yayında kaynak penceresi
    fun longOk() {
        if (listOpen) {
            if (col == 1) openMenu()
        } else if (pickerList.size > 1) {
            openSources()
        }
    }

    fun menuChoose(i: Int) {
        val m = menu ?: return
        menu = null
        if (!m.confirm) {
            when (i) {
                0 -> editing = m.ch
                1 -> {
                    moving = true
                    showToast("Taşıma: ↑/↓ ile yer değiştir · OK ile bitir")
                }
                // Silme onayı: imleç güvenli seçenek olan "Vazgeç"te başlar
                2 -> menu = ChannelMenu(m.ch, confirm = true, sel = 1)
            }
        } else if (i == 0) {
            s.deleteChannel(m.ch) { showToast(it) }
        }
    }

    // Tuşlanan numara: liste açıksa imleci, kapalıysa kanalı o numaraya götürür
    fun commitDigits() {
        val n = digits.toIntOrNull() ?: return
        digits = ""
        val target = if (listOpen) list else s.inGroup(s.group)
        val ch = target.getOrNull(n - 1) ?: return showToast("$n numaralı kanal yok")
        if (listOpen) {
            col = 1
            cSel = n - 1
        } else if (ch != current) {
            s.play(ch, s.group)
            bannerTick++
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(digitTick) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1500)
        commitDigits()
    }
    LaunchedEffect(toastTick) {
        delay(2000)
        toast = null
    }
    // Kanalın kimliğine bağlı: liste yenilenip ad/logo değişince oynayan kanal "Bağlanıyor"a düşmesin
    LaunchedEffect(current?.id) {
        status = PlayerStatus.Loading
        forcedSource = null
    }
    // Kanal açıldıktan sonra bir kez: diğer kaynaklar arka planda denenir (durumları güncellenir); daha kaliteli
    // açılan varsa sessizce ona geçilir. Yalnızca izlemenin başında: sonradan kaynak değiştirilmez.
    val playingNow by rememberUpdatedState(playing)
    // Kaliteli kaynağa geçilirken "Bağlanıyor" gösterilmez; geçiş sessiz olsun
    var upgrading by remember { mutableStateOf(false) }
    LaunchedEffect(current?.id) {
        upgrading = false
        val ch = current ?: return@LaunchedEffect
        snapshotFlow { status }.first { it == PlayerStatus.Playing }
        delay(3_000) // görüntü yüksekliği oynatıcıdan gelsin
        val start = playingNow ?: return@LaunchedEffect
        val mark = TimeSource.Monotonic.markNow()
        val better = s.findBetter(ch, start)
        if (forcedSource != null || playingNow?.id != start.id) return@LaunchedEffect // kullanıcı elle değiştirdi
        if (better == null) {
            // Daha kalitelisi yok: hızlı açılan bu kaynak, sonraki açılışta da önce denensin
            if (status == PlayerStatus.Playing) s.rememberBest(ch, start)
            return@LaunchedEffect
        }
        if (mark.elapsedNow() >= 30.seconds) return@LaunchedEffect
        upgrading = true
        forcedSource = better.id
        // Kaliteli kaynak gerçekten açılırsa hatırlanır; açılmazsa oynatıcı sıradakine geçer, hatırlanmaz
        val opened = withTimeoutOrNull(20.seconds) {
            snapshotFlow { status to playingNow?.id }.first { (st, id) -> st == PlayerStatus.Playing && id == better.id }
        }
        upgrading = false
        if (opened != null) s.rememberBest(ch, better)
    }
    LaunchedEffect(srcOpen, srcSel) { if (srcOpen) srcState.ensureVisible(srcSel) }
    // Açılamayan yayınları işaretle: oynatıcı kendi linkini ve yedeklerini denedikten sonra hata verirse.
    // Kanal kendi linkiyle açılınca işaret kalkar (yedekle açılması kendi linkinin çalıştığını göstermez).
    LaunchedEffect(current?.id, status) {
        val ch = current ?: return@LaunchedEffect
        when (status) {
            PlayerStatus.Playing -> if (sources.getOrNull(opts.source)?.id == ch.id) s.markBroken(ch, false)
            PlayerStatus.Error -> s.markBroken(ch, true)
            PlayerStatus.Loading -> Unit
        }
    }
    // Yayın akışı için saat
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = nowMs()
        }
    }
    LaunchedEffect(editing) { if (editing == null) focus.requestFocus() }
    LaunchedEffect(touchTick) {
        if (!touchBar) return@LaunchedEffect
        delay(5_000)
        touchBar = false
    }
    LaunchedEffect(bannerTick) {
        bannerVisible = true
        delay(3500)
        bannerVisible = false
    }
    // Kaynak yazısı kanal ya da kaynak değişince 2 sn görünür, sonra kaybolur
    var pillVisible by remember { mutableStateOf(false) }
    LaunchedEffect(current?.id, playing?.id) {
        pillVisible = true
        delay(2000)
        pillVisible = false
    }
    LaunchedEffect(listOpen, gSel) { if (listOpen) gState.ensureVisible(gSel) }
    LaunchedEffect(listOpen, cSel, gSel) {
        if (listOpen) cState.ensureVisible(cSel)
    }
    // Liste kapanınca odak tekrar oynatıcıya dönsün
    LaunchedEffect(listOpen) { focus.requestFocus() }

    // Silmeden sonra liste kısalınca imleç dışarıda kalmasın
    LaunchedEffect(list.size) { cSel = cSel.coerceIn(0, (list.size - 1).coerceAtLeast(0)) }

    // Tam ekranda Geri uygulamayı kapatmaz, kanal listesini açar; çıkış listedeki "Çıkış" satırından
    PlatformBackHandler(enabled = !listOpen) { openList() }
    PlatformBackHandler(enabled = listOpen) { listOpen = false }
    PlatformBackHandler(enabled = menu != null) { menu = null }
    PlatformBackHandler(enabled = moving) { moving = false }
    PlatformBackHandler(enabled = optsOpen) { optsOpen = false }
    PlatformBackHandler(enabled = srcOpen) { srcOpen = false }
    PlatformBackHandler(enabled = s.scan != null) { s.cancelScan() }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                // Arama kutusuna yazılırken tuşlar kutuya gider; Aşağı ile sonuçlara inilir
                if (editing != null) return@onPreviewKeyEvent false
                // Tarama sürerken yalnızca Geri (iptal) çalışır
                if (s.scan != null) {
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Back || e.key == Key.Escape)) s.cancelScan()
                    return@onPreviewKeyEvent true
                }
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (e.type == KeyEventType.KeyUp) {
                    val down = okDown
                    if (!isOk || down == null) return@onPreviewKeyEvent false
                    okDown = null
                    when {
                        okLongDone -> okLongDone = false
                        srcOpen -> srcChoose(srcSel)
                        optsOpen -> optChoose(optSel)
                        menu != null -> menuChoose(menu!!.sel)
                        moving -> {
                            moving = false
                            showToast("Sıralama kaydedildi")
                        }
                        digits.isNotEmpty() -> commitDigits()
                        down.elapsedNow() >= LONG_PRESS -> longOk()
                        !listOpen -> openList()
                        else -> choose()
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (isOk) {
                    val down = okDown
                    if (down == null) {
                        okDown = TimeSource.Monotonic.markNow()
                    } else if (menu == null && !moving && !optsOpen && !srcOpen && !okLongDone && (e.isRepeat() || down.elapsedNow() >= LONG_PRESS)) {
                        // Tuş basılı tutulurken gelen tekrar olayı: bırakmayı beklemeden menüyü aç
                        okLongDone = true
                        longOk()
                    }
                    return@onPreviewKeyEvent true
                }
                if (srcOpen) {
                    when (e.key) {
                        Key.DirectionUp -> srcSel = (srcSel - 1).coerceAtLeast(0)
                        Key.DirectionDown -> srcSel = (srcSel + 1).coerceAtMost(pickerList.size + 2)
                        Key.DirectionLeft, Key.Back, Key.Escape -> srcOpen = false
                        else -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                if (optsOpen) {
                    when (e.key) {
                        Key.DirectionUp -> optSel = (optSel - 1).coerceAtLeast(0)
                        Key.DirectionDown -> optSel = (optSel + 1).coerceAtMost(4)
                        Key.DirectionLeft, Key.Back, Key.Escape, Key.Menu -> optsOpen = false
                        else -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                menu?.let { m ->
                    when (e.key) {
                        Key.DirectionUp -> menu = m.withSel((m.sel - 1).coerceAtLeast(0))
                        Key.DirectionDown -> menu = m.withSel((m.sel + 1).coerceAtMost(m.size - 1))
                        Key.DirectionLeft, Key.Back, Key.Escape -> menu = null
                        else -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                if (moving) {
                    val ch = list.getOrNull(cSel)
                    when (e.key) {
                        Key.DirectionUp -> if (ch != null && s.move(ch, list, -1)) cSel--
                        Key.DirectionDown -> if (ch != null && s.move(ch, list, 1)) cSel++
                        Key.DirectionLeft, Key.Back, Key.Escape -> {
                            moving = false
                            showToast("Sıralama kaydedildi")
                        }
                        else -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                e.key.digit()?.let {
                    digits = (digits + it).takeLast(4)
                    digitTick++
                    return@onPreviewKeyEvent true
                }
                if (!listOpen) {
                    when (e.key) {
                        Key.DirectionUp, Key.ChannelUp -> zap(1)
                        Key.DirectionDown, Key.ChannelDown -> zap(-1)
                        Key.LastChannel -> lastChannel()
                        // Sağ/sol: kanalın birden çok kaynağı varsa kaynak değiştirir; tek kaynakta son kanal / liste
                        Key.DirectionLeft -> if (pickerList.size > 1) stepSource(-1) else lastChannel()
                        Key.DirectionRight -> if (pickerList.size > 1) stepSource(1) else openList()
                        Key.Menu, Key.Info -> { optsOpen = true; optSel = 0 }
                        else -> return@onPreviewKeyEvent false
                    }
                } else {
                    when (e.key) {
                        Key.DirectionUp -> if (col == 1) { if (cSel == 0) col = 0 else cSel-- }
                        Key.DirectionDown -> if (col == 0) {
                            if (list.isNotEmpty()) { col = 1; cSel = cSel.coerceAtMost(list.lastIndex) }
                        } else if (list.isNotEmpty()) {
                            cSel = (cSel + 1).coerceAtMost(list.lastIndex)
                        }
                        // Sekmelerde sağ/sol kategori değiştirir; kanal listesinde sol sekmelere döner, sağ kanal menüsünü açar
                        Key.DirectionLeft -> if (col == 0) { gSel = (gSel - 1).coerceAtLeast(0); cSel = 0 } else col = 0
                        Key.DirectionRight -> if (col == 0) { gSel = (gSel + 1).coerceAtMost(groupItems.lastIndex); cSel = 0 } else openMenu()
                        Key.Back, Key.Escape -> listOpen = false
                        Key.ChannelUp -> zap(1)
                        Key.ChannelDown -> zap(-1)
                        else -> return@onPreviewKeyEvent false
                    }
                }
                true
            },
    ) {
        // Yayın her zaman tam ekran; liste ve bilgi bunun üstünde açılır pencere olarak görünür
        // Tarama sırasında yayın durur: panel linkleri tek bağlantıya izin verebilir, ölçümler de bozulmasın
        val scanning = s.scan
        if (scanning == null) {
            VideoPlayer(
                sources = sources,
                preload = s.neighbors(),
                options = opts,
                onStatus = { status = it },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val (done, total) = scanning
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 520.dp).fillMaxWidth(0.8f)
                    .background(Glass, RoundedCornerShape(20.dp)).border(1.dp, Line, RoundedCornerShape(20.dp))
                    .clickable { s.cancelScan() }
                    .padding(24.dp),
            ) {
                Text("Kaynaklar taranıyor", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text("Her linkin açılışı, hızı ve çözünürlüğü ölçülüyor", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Line)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(done.toFloat() / total.coerceAtLeast(1)).background(Accent))
                }
                Spacer(Modifier.height(10.dp))
                Text("$done / $total link · iptal için Geri ya da dokun", color = Muted, fontSize = 13.sp)
            }
        }

        if (!listOpen) {
            // Dokunmatik: dokun = düğme çubuğu, uzun bas = görüntü/ses, yukarı/aşağı kaydır = kanal, sağa kaydır = son kanal
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { touchBar = !touchBar; touchTick++ }, onLongPress = { optsOpen = true; optSel = 0 })
                    }
                    .pointerInput(Unit) {
                        var dx = 0f
                        var dy = 0f
                        detectDragGestures(
                            onDragStart = { dx = 0f; dy = 0f },
                            onDragEnd = {
                                when {
                                    abs(dy) > abs(dx) && dy < -80f -> zap(1)
                                    abs(dy) > abs(dx) && dy > 80f -> zap(-1)
                                    abs(dx) > abs(dy) && dx > 80f -> lastChannel()
                                }
                            },
                        ) { _, d -> dx += d.x; dy += d.y }
                    },
            )
            // Açılmayan kaynakta uyarı yok: oynatıcı sessizce sıradaki kaynağı dener
            if ((bannerVisible || (status == PlayerStatus.Loading && !upgrading)) && current != null && s.scan == null) {
                Banner(s, current, status, now, Modifier.align(Alignment.BottomStart).padding(32.dp))
            }
            // Sağ altta kaynak yazısı: kanalın birden çok yayını varsa hangisinin oynadığını gösterir, dokununca seçtirir
            if (pillVisible && pickerList.size > 1 && playing != null && s.scan == null) {
                val k = pickerList.indexOfFirst { it.id == playing.id } + 1
                Text(
                    "⇄  Kaynak $k/${pickerList.size} · ${s.sourceLabel(playing)}",
                    color = TextC,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(pad)
                        .clip(RoundedCornerShape(20.dp)).background(Color(0xCC111112))
                        .border(1.dp, Color(0x66E5484D), RoundedCornerShape(20.dp))
                        .clickable { openSources() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            if (touchBar) {
                // Her dokunuşta çubuğun kapanma süresi yeniden başlar
                fun bar(f: () -> Unit): () -> Unit = { f(); touchTick++ }
                Row(
                    Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 16.dp)
                        .background(Color(0xDD111112), RoundedCornerShape(28.dp)).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Dokunmatikte (iOS) taramayı iptal etmenin yolu: tarama sürerken Kanallar önce taramayı durdurur
                    Chip("☰  Kanallar", false) {
                        touchBar = false
                        if (s.scan != null) {
                            s.cancelScan()
                            showToast("Tarama iptal edildi")
                        }
                        openList()
                    }
                    Chip("▲  Sonraki", false, onClick = bar { zap(1) })
                    Chip("▼  Önceki", false, onClick = bar { zap(-1) })
                    Chip("↩  Son kanal", false, onClick = bar { lastChannel() })
                    Chip("⚙", false) { touchBar = false; optsOpen = true; optSel = 0 }
                }
            }
        } else {
            // Pencerelerin dışına (yayına) dokununca liste kapanır
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { listOpen = false } })
            // Üstte yarı saydam menü: kategoriler sekme olarak, altında kanallar
            Column(
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = pad, vertical = if (compact) 6.dp else 24.dp)
                    .fillMaxWidth(if (compact) 0.94f else 0.8f).fillMaxHeight(if (compact) 0.6f else 0.7f)
                    .background(Glass, RoundedCornerShape(20.dp))
                    .border(1.dp, Line, RoundedCornerShape(20.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp),
            ) {
                LazyRow(Modifier.fillMaxWidth(), state = gState, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    itemsIndexed(groupItems) { i, g ->
                        val isAction = g !in s.groups
                        val on = i == gSel && col == 0
                        val picked = i == gSel && col != 0
                        Column(
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (on) Sel else Color.Transparent)
                                .clickable { gSel = i; col = 0; choose() }
                                .padding(horizontal = 14.dp, vertical = if (compact) 6.dp else 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (g == ACT_REFRESH) {
                                // MuratTv yazısı: basınca liste yenilenir
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(Accent))
                                    Spacer(Modifier.width(8.dp))
                                    if (s.loading) {
                                        Text("Yenileniyor…", color = if (on) OnAccent else Muted, fontSize = 15.sp)
                                    } else {
                                        Text("Murat", color = if (on) OnAccent else TextC, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                        Text("Tv", color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                        if (on) Text("  ↻", color = OnAccent, fontSize = 15.sp)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            } else {
                                Text(
                                    if (g == ACT_RESTORE) "$g (${s.hidden.size})" else g,
                                    color = when {
                                        on -> OnAccent
                                        picked -> TextC
                                        isAction -> Muted
                                        else -> Color(0xFFB4B4B8)
                                    },
                                    fontSize = 14.sp,
                                    fontWeight = if (on || picked) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                )
                                // Açık sekmenin altında kırmızı çizgi
                                Box(Modifier.padding(top = 4.dp).width(22.dp).height(2.dp).background(if (picked) Accent else Color.Transparent, RoundedCornerShape(1.dp)))
                            }
                        }
                    }
                }
                Box(Modifier.padding(vertical = if (compact) 6.dp else 10.dp).fillMaxWidth().height(1.dp).background(Line))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = cState) {
                    itemsIndexed(list, key = { i, ch -> "$i|${ch.url}" }) { i, ch ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(8.dp))
                                .background(rowBg(selected = i == cSel, focused = col == 1))
                                .combinedClickable(
                                    onLongClick = { cSel = i; col = 1; openMenu() },
                                ) { cSel = i; col = 1; choose() }
                                .padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val on = i == cSel && col == 1
                            val dead = s.isDead(ch)
                            Text("${i + 1}", color = rowFg(on, Muted), fontSize = 12.sp, modifier = Modifier.width(40.dp))
                            Logo(s.logoOf(ch), Modifier.size(46.dp, 30.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF232325)).padding(3.dp))
                            Spacer(Modifier.width(12.dp))
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (on && moving) "⇅  ${ch.name}" else ch.name,
                                    color = rowFg(on, if (dead) Muted else TextC),
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false).padding(end = 10.dp),
                                )
                                QualityBadge(s.tierOf(ch))
                            }
                            if (dead) {
                                Text("açılmadı", color = if (on) OnAccent else Color(0xFFFF8A8E), fontSize = 11.sp, modifier = Modifier.padding(end = 10.dp))
                            }
                            val backups = s.backupCount(ch)
                            if (backups > 0) {
                                Text(if (opts.mergeDuplicates) "${backups + 1} kaynak" else "+$backups yedek", color = rowFg(on, Muted), fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
                            }
                            if (ch == current) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) OnAccent else Accent))
                            }
                        }
                    }
                }
                if (moving) {
                    Text("Taşıma modu · ↑/↓: yer değiştir · OK: bitir", color = Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                } else if (!compact) {
                    Text(
                        "←/→ kategori  ·  → ya da basılı OK: kanal menüsü  ·  0-9 numara  ·  Geri kapat",
                        color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp, start = 6.dp),
                    )
                }
            }
            // Altta ince bilgi şeridi: oynayan kanal, yayın akışı ve dokunmatik düğmeler
            Row(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = pad, vertical = if (compact) 10.dp else 28.dp)
                    .fillMaxWidth(if (compact) 0.94f else 0.8f)
                    .background(Glass, RoundedCornerShape(16.dp))
                    .border(1.dp, Line, RoundedCornerShape(16.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (current != null) {
                    Logo(s.logoOf(current), Modifier.size(56.dp, 36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.width(if (compact) 140.dp else 220.dp)) {
                        Text(current.name, color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (s.loading) "Liste güncelleniyor…" else "${current.group} · ${statusText(status)}",
                            color = if (status == PlayerStatus.Error) Color(0xFFFF8A8E) else Muted, fontSize = 12.sp, maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.width(1.dp).height(36.dp).background(Line))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) { NowNext(s, current, now, detailed = false, wide = true) }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(12.dp))
                // Dokunmatik ekranlar için: kumandadaki tuşların karşılığı
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (moving) {
                        fun step(d: Int) {
                            val ch = list.getOrNull(cSel)
                            if (ch != null && s.move(ch, list, d)) cSel += d
                        }
                        Chip("▲", false) { step(-1) }
                        Chip("▼", false) { step(1) }
                        Chip("✓  Bitti", true) { moving = false; showToast("Sıralama kaydedildi") }
                    } else {
                        Chip("✕", false) { listOpen = false }
                        if (pickerList.size > 1) {
                            Chip("⇄  ${pickerList.indexOfFirst { it.id == playing?.id } + 1}/${pickerList.size}", false) { openSources() }
                        }
                        Chip("⚙", false) { optsOpen = true; optSel = 0 }
                    }
                }
            }
        }

        if (digits.isNotEmpty()) {
            Text(
                digits,
                color = TextC,
                fontSize = 44.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.TopStart).padding(32.dp)
                    .background(Glass, RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 6.dp),
            )
        }
        if (srcOpen && current != null) {
            val rows = pickerList.mapIndexed { i, c ->
                buildString {
                    append("Kaynak ${i + 1} · ${s.sourceLabel(c)}")
                    if (c.id == playing?.id) append("  ▶ oynuyor")
                    if (s.pins[s.repOf(current).id] == c.id) append("  ★")
                }
            } + listOf(
                "★  Oynayanı bu kanal için varsayılan yap",
                if (playing != null && s.isAvoided(playing)) "↺  Oynayanı tekrar kullan" else "⊘  Oynayanı istemiyorum (reklam kuşağı vb.)",
                "Kapat",
            )
            Box(
                Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures { srcOpen = false } },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(pad).width(440.dp).panel()
                        .pointerInput(Unit) { detectTapGestures { } }.padding(18.dp),
                ) {
                    PanelHeader("${current.name} · kaynaklar", "En iyisi kendiliğinden seçilir; istediğini seçebilirsin")
                    LazyColumn(Modifier.heightIn(max = if (compact) 220.dp else 460.dp), state = srcState) {
                        itemsIndexed(rows) { i, label ->
                            // Kaynak satırı: "Kaynak 2" solda, "1080p · hızlı  ▶ oynuyor" sağda
                            val isSource = i < pickerList.size
                            PanelRow(
                                if (isSource) label.substringBefore(" · ") else label,
                                if (isSource) label.substringAfter(" · ", "") else null,
                                on = i == srcSel,
                                color = when {
                                    label.contains("açılmadı") -> Color(0xFFFF8A8E)
                                    i == rows.lastIndex -> Muted
                                    else -> TextC
                                },
                            ) { srcSel = i; srcChoose(i) }
                        }
                    }
                }
            }
        }
        if (optsOpen) {
            val audio = when {
                opts.audioTracks.size <= 1 -> "Yayının varsayılanı"
                opts.audioIndex < 0 -> "Yayının varsayılanı (${opts.audioTracks.size} seçenek)"
                else -> opts.audioTracks[opts.audioIndex]
            }
            val rows = listOf(
                "Görüntü" to opts.aspect.label,
                "Ses" to audio,
                "Aynı kanalları birleştir" to if (opts.mergeDuplicates) "Açık" else "Kapalı",
                "Sıralamayı eşitle" to null,
                "Kapat" to null,
            )
            // Panelin dışına dokununca kapanır
            Box(
                Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures { optsOpen = false } },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(pad).width(400.dp).panel()
                        .pointerInput(Unit) { detectTapGestures { } }.padding(18.dp),
                ) {
                    PanelHeader("Görüntü ve ses", "OK değiştir  ·  Geri kapat")
                    rows.forEachIndexed { i, (label, value) ->
                        PanelRow(label, value, on = i == optSel, color = if (i == rows.lastIndex) Muted else TextC) { optSel = i; optChoose(i) }
                    }
                }
            }
        }
        menu?.let { m ->
            val labels = if (m.confirm) {
                listOf("Evet, sil", "Vazgeç")
            } else {
                listOf("✎  Düzenle (ad, kategori, logo)", "⇅  Taşı (sırala)", "Kanalı sil", "Vazgeç")
            }
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { menu = null } },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.width(400.dp).panel()
                        .pointerInput(Unit) { detectTapGestures { } }.padding(20.dp),
                ) {
                    Text(
                        if (m.confirm) "${m.ch.name} silinsin mi?" else m.ch.name,
                        color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (m.confirm) {
                        val toGist = Gist.idFrom(s.url) != null && s.token.isNotBlank()
                        Text(
                            if (toGist) "Gist'teki listeden de çıkarılır; \"Silinenleri geri getir\" ile geri alınabilir." else "Yalnızca bu cihazdan silinir (Gist için ayarlara token ekle).",
                            color = Muted, fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    labels.forEachIndexed { i, label ->
                        val on = i == m.sel
                        val danger = (m.confirm && i == 0) || (!m.confirm && i == 3)
                        PanelRow(label, null, on = on, color = if (danger) Color(0xFFFF8A8E) else TextC) { menuChoose(i) }
                    }
                }
            }
        }
        editing?.let { ch ->
            EditChannelScreen(s, ch, onToast = { showToast(it) }, onClose = { editing = null })
        }
        toast?.let {
            Text(
                it,
                color = TextC,
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                    .background(Glass, RoundedCornerShape(22.dp))
                    .border(1.dp, Line, RoundedCornerShape(22.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * Kanalın adını, kategorisini ve logosunu değiştirme ekranı (HTML düzenleyicideki gibi).
 * Kumandayla: yukarı/aşağı alanlar ve satırlar arasında, sağ/sol satır içinde gezer; OK seçer.
 */
@Composable
private fun EditChannelScreen(s: AppState, ch: Channel, onToast: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(ch.name) }
    var group by remember { mutableStateOf(ch.group) }
    var logo by remember { mutableStateOf(ch.logo.orEmpty()) }
    var candidates by remember { mutableStateOf(emptyList<String>()) }
    var finding by remember { mutableStateOf(false) }
    var findNote by remember { mutableStateOf<String?>(null) }
    var typeUrl by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstTile = remember { FocusRequester() }
    val searchChip = remember { FocusRequester() }
    PlatformBackHandler(enabled = true) { onClose() }

    fun findLogos() {
        scope.launch {
            finding = true
            findNote = null
            try {
                candidates = LogoFinder.find(name, s.token)
                if (candidates.isEmpty()) findNote = "Bu adla logo bulunamadı; adı düzeltip yeniden ara"
            } catch (e: Throwable) {
                findNote = e.message ?: "Logo listesi alınamadı"
            } finally {
                finding = false
            }
        }
    }

    // Açılınca logolar kendiliğinden aranır; ilk odak resimlerde (metin kutusu klavyeyi açıp ekranı kapatmasın)
    LaunchedEffect(Unit) {
        if (ch.logo.isNullOrBlank()) searchChip.requestFocus() else firstTile.requestFocus()
        findLogos()
    }

    fun save() {
        val e = ChannelEdit(
            name = name.trim().takeIf { it.isNotEmpty() && it != ch.name },
            group = group.trim().takeIf { it.isNotEmpty() && it != ch.group },
            logo = logo.trim().takeIf { it != ch.logo.orEmpty() },
        )
        if (e.name == null && e.group == null && e.logo == null) {
            onToast("Değişiklik yok")
        } else {
            s.editChannel(ch, e, onToast)
        }
        onClose()
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp, 60.dp).background(Panel, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    if (logo.isNotBlank()) {
                        AsyncImage(logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Kanalı düzenle", color = TextC, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Text(ch.id, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Logo — resme gelip OK'a bas", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            val tiles = (listOfNotNull(ch.logo?.takeIf { it.isNotBlank() }) + candidates).distinct()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(tiles) { i, url ->
                    LogoChoice(url, selected = url == logo, if (i == 0) Modifier.focusRequester(firstTile) else Modifier) { logo = url }
                }
                item { LogoChoice(null, selected = logo.isBlank()) { logo = "" } }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(if (finding) "Aranıyor…" else "Yeniden ara", selected = false, Modifier.focusRequester(searchChip)) {
                    if (!finding) findLogos()
                }
                Chip("Adresi elle yaz", selected = typeUrl) { typeUrl = !typeUrl }
                findNote?.let { Text(it, color = Muted, fontSize = 13.sp) }
            }
            if (typeUrl) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    logo, { logo = it },
                    singleLine = true,
                    placeholder = { Text("https://…", color = Muted) },
                    modifier = Modifier.fillMaxWidth().dpadFocus(),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text("Ad", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().dpadFocus())
            Spacer(Modifier.height(14.dp))
            Text("Kategori", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(group, { group = it }, singleLine = true, modifier = Modifier.fillMaxWidth().dpadFocus())
            Spacer(Modifier.height(6.dp))
            // Var olan kategoriler: yazmak yerine seçmek için
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.realGroups()) { g ->
                    Chip(g, selected = g == group) { group = g }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ save() }) { Text("Kaydet") }
                OutlinedButton(onClose) { Text("Vazgeç") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (Gist.idFrom(s.url) != null && s.token.isNotBlank()) "Gist'teki listeye de yazılır; PC'de gist_guncelle.py yerel listene de uygular."
                else "Yalnızca bu cihazda kaydedilir (Gist için ayarlara token ekle).",
                color = Muted, fontSize = 12.sp,
            )
        }
    }
}

/** Kumandayla odaklanınca belirginleşen seçim kutusu. */
@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = if (focused) OnAccent else if (selected) Accent else TextC,
        fontSize = 14.sp,
        modifier = modifier.clip(RoundedCornerShape(16.dp))
            .background(if (focused) Sel else Panel)
            .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun LogoChoice(url: String?, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.size(150.dp, 90.dp).clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color(0xFF2A1C1D) else Panel)
            .border(
                if (focused || selected) 3.dp else 1.dp,
                if (focused) Accent else if (selected) TextC else Line,
                RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Text("Logo yok", color = Muted, fontSize = 13.sp)
        } else {
            AsyncImage(url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (selected) {
            Text("✓", color = OnAccent, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopEnd).background(Accent, CircleShape).padding(horizontal = 5.dp))
        }
    }
}

/** Kanal menüsü; [confirm] true ise silme onayı gösterilir. */
private class ChannelMenu(val ch: Channel, val confirm: Boolean = false, val sel: Int = 0) {
    val size get() = if (confirm) 2 else 5
    fun withSel(i: Int) = ChannelMenu(ch, confirm, i)
}

private fun Key.digit(): Char? = when (this) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

@Composable
private fun Banner(s: AppState, ch: Channel, status: PlayerStatus, now: Long, modifier: Modifier) {
    val no = s.inGroup(s.group).indexOf(ch) + 1
    Row(
        modifier.widthIn(max = 720.dp).background(Glass, RoundedCornerShape(16.dp))
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("$no", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Logo(s.logoOf(ch), Modifier.size(64.dp, 40.dp))
        Column {
            Text(ch.name, color = TextC, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text("${s.group} · ${statusText(status)}", color = Muted, fontSize = 12.sp)
            NowNext(s, ch, now, detailed = false)
        }
    }
}

@Composable
private fun Logo(url: String?, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 4K / FHD / HD rozeti (PNG). */
@Composable
private fun QualityBadge(tier: Int) {
    val (res, ratio) = when (tier) {
        3 -> Res.drawable.badge_4k to 77f / 54f
        2 -> Res.drawable.badge_fhd to 103f / 54f
        1 -> Res.drawable.badge_hd to 81f / 54f
        else -> return
    }
    Image(painterResource(res), contentDescription = null, modifier = Modifier.padding(end = 8.dp).height(15.dp).aspectRatio(ratio))
}

/** Yayın akışı: şimdi oynayan (ilerleme çubuğuyla) ve sıradaki program. */
@Composable
private fun NowNext(s: AppState, ch: Channel, now: Long, detailed: Boolean, wide: Boolean = false) {
    val (cur, next) = s.epg?.nowNext(ch, now) ?: return
    if (cur == null && next == null) return
    Spacer(Modifier.height(6.dp))
    if (cur != null) {
        Text(
            "${formatClock(cur.start)}–${formatClock(cur.stop)}  ${cur.title}",
            color = TextC, fontSize = if (detailed) 15.sp else 13.sp,
            maxLines = if (detailed) 2 else 1, overflow = TextOverflow.Ellipsis,
        )
        val p = ((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f)
        Box(Modifier.padding(vertical = 4.dp).then(if (wide) Modifier.fillMaxWidth() else Modifier.width(if (detailed) 260.dp else 200.dp)).height(3.dp).background(Line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(Accent))
        }
    }
    if (next != null) {
        Text(
            "Sonra ${formatClock(next.start)}  ${next.title}",
            color = Muted, fontSize = if (detailed) 13.sp else 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SetupScreen(s: AppState) {
    var text by remember { mutableStateOf(s.url) }
    var token by remember { mutableStateOf(s.token) }
    var pasteNote by remember { mutableStateOf<String?>(null) }
    @Suppress("DEPRECATION")
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var epgUrl by remember { mutableStateOf(s.epgUrl) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    PlatformBackHandler(enabled = s.channels.isNotEmpty()) { s.showSetup = false }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("M3U liste adresi", color = TextC, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().dpadFocus())
        Spacer(Modifier.height(20.dp))
        Text("GitHub token (isteğe bağlı)", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(
            "Silme ve sıralama eşitlemesi Gist'e yazabilsin diye. Yalnızca \"gist\" yetkili klasik token kullan.",
            color = Muted, fontSize = 13.sp,
        )
        Text(
            if (s.token.isBlank()) "Kayıtlı token yok" else "✓ Token kayıtlı (…${s.token.takeLast(4)})",
            color = if (s.token.isBlank()) Color(0xFFFF8A8E) else Accent, fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            token, { token = it },
            singleLine = true,
            placeholder = { Text("Token'ı buraya yapıştır (ghp_ ile başlar)", color = Muted) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().dpadFocus(),
        )
        Spacer(Modifier.height(6.dp))
        // Uzun basıp yapıştırmak kumandada ve iPhone'da zor: tek dokunuşla panodaki metni alır
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("📋  Panodan yapıştır", false) {
                val pasted = clipboard.getText()?.text?.trim().orEmpty()
                if (pasted.isNotEmpty()) token = pasted
                pasteNote = if (pasted.isEmpty()) "Pano boş" else "Yapıştırıldı (…${pasted.takeLast(4)}) · Kaydet'e bas"
            }
            if (token.isNotEmpty()) Chip("Temizle", false) { token = ""; pasteNote = null }
            pasteNote?.let { Text(it, color = Muted, fontSize = 13.sp) }
        }
        Spacer(Modifier.height(20.dp))
        Text("Yayın akışı (EPG) adresi", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text("XMLTV (.xml ya da .xml.gz). Boş bırakılırsa yayın akışı gösterilmez.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(epgUrl, { epgUrl = it }, singleLine = true, modifier = Modifier.fillMaxWidth().dpadFocus())
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ s.saveSettings(text, token, epgUrl) }, Modifier.focusRequester(first)) { Text("Kaydet") }
            OutlinedButton({ text = DEFAULT_URL; epgUrl = DEFAULT_EPG_URL }) { Text("Varsayılanlar") }
            if (s.channels.isNotEmpty()) OutlinedButton({ s.showSetup = false }) { Text("Vazgeç") }
        }
    }
}

/** Metin kutusunda kumandanın yukarı/aşağı tuşları imleç yerine bir önceki/sonraki alana geçsin. */
@Composable
private fun Modifier.dpadFocus(): Modifier {
    val fm = LocalFocusManager.current
    return onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionUp -> fm.moveFocus(FocusDirection.Up)
            Key.DirectionDown -> fm.moveFocus(FocusDirection.Down)
            else -> false
        }
    }
}

@Composable
private fun Message(text: String, action: String, onAction: () -> Unit) {
    val f = remember { FocusRequester() }
    LaunchedEffect(Unit) { f.requestFocus() }
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = TextC, fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))
        Button(onAction, Modifier.focusRequester(f)) { Text(action) }
    }
}

/** Açılır panellerin zemini: koyu cam, ince çerçeve. */
private fun Modifier.panel() = background(Color(0xF5151517), RoundedCornerShape(18.dp)).border(1.dp, Line, RoundedCornerShape(18.dp))

@Composable
private fun PanelHeader(title: String, hint: String) {
    Text(title, color = TextC, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(hint, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(Line))
}

/** Panel satırı: solda ad, sağda değer; seçiliyken beyaz zemin. */
@Composable
private fun PanelRow(label: String, value: String?, on: Boolean, color: Color = TextC, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
            .background(if (on) Sel else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, color = if (on) OnAccent else color, fontSize = 15.sp,
            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (!value.isNullOrBlank()) {
            Text(
                value, color = if (on) Color(0xFF55555C) else Muted, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).widthIn(max = 220.dp),
            )
        }
    }
}

private fun rowBg(selected: Boolean, focused: Boolean) = when {
    selected && focused -> Sel
    selected -> Panel
    else -> Color.Transparent
}

private fun rowFg(onAccent: Boolean, normal: Color) = if (onAccent) OnAccent else normal

private fun statusText(s: PlayerStatus) = when (s) {
    PlayerStatus.Loading -> "Bağlanıyor…"
    PlayerStatus.Playing -> "Canlı"
    PlayerStatus.Error -> "Yayın açılamadı"
}

private suspend fun LazyListState.ensureVisible(index: Int) {
    val info = layoutInfo.visibleItemsInfo
    if (info.isEmpty()) return scrollToItem(index)
    val first = info.first().index
    val last = info.last().index
    if (index <= first || index >= last) scrollToItem((index - info.size / 2).coerceAtLeast(0))
}
