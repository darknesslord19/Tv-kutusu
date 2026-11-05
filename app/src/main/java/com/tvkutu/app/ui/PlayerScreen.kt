package com.tvkutu.app.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tvkutu.app.AppViewModel
import com.tvkutu.app.data.Channel
import com.tvkutu.app.data.Repository
import com.tvkutu.app.data.WatchProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

private enum class Overlay { None, Banner, Controls, Channels, Guide, Tracks }

/** Basılı tutuldukça artan sarma adımı (ms). */
private fun seekStep(repeatCount: Int): Long = when {
    repeatCount > 14 -> 60_000L
    repeatCount > 4 -> 30_000L
    else -> 10_000L
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channel = vm.currentChannel ?: return

    // Kanal/bölüm değiştirme, açıldığı kategori içinde yapılır.
    val list = remember(vm.selectedGroup, vm.playlist, vm.favorites) {
        vm.channelsIn(vm.selectedGroup).ifEmpty { vm.channelsIn(AppViewModel.ALL) }
    }

    var overlay by remember { mutableStateOf(Overlay.Banner) }
    var status by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var retries by remember { mutableIntStateOf(0) }
    var forceHls by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var sourceKind by remember { mutableStateOf("") }
    var tech by remember { mutableStateOf("ExoPlayer") }

    // Film/dizi (sarılabilir) mı, canlı mı?
    var vod by remember { mutableStateOf(false) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    var bufMs by remember { mutableLongStateOf(0L) }
    var bubble by remember { mutableStateOf<String?>(null) }
    var bubbleJob by remember { mutableStateOf<Job?>(null) }
    var tracksVersion by remember { mutableIntStateOf(0) }
    var lastInput by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val seekTarget = remember { longArrayOf(-1L, 0L) } // hedef, zaman
    val playingRef = remember { arrayOfNulls<Channel>(1) }

    val rootFocus = remember { FocusRequester() }

    val player = remember {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers).build().apply { playWhenReady = true }
    }

    fun knownDuration(): Long = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    fun updateVod() {
        vod = !player.isCurrentMediaItemLive && player.isCurrentMediaItemSeekable && knownDuration() > 0
    }

    fun saveProgressNow() {
        val ch = playingRef[0] ?: return
        val d = knownDuration()
        if (vod && d > 0) vm.updateProgress(ch, player.currentPosition, d)
    }

    fun prepare(ch: Channel, hls: Boolean, startMs: Long = 0L) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ch.userAgent ?: Repository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        ch.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val format = vm.streamFormat
        val mime = when {
            format == "hls" || hls -> MimeTypes.APPLICATION_M3U8
            format == "ts" -> MimeTypes.VIDEO_MP2T
            ch.url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
        sourceKind = when (mime) {
            MimeTypes.APPLICATION_M3U8 -> "HLS"
            MimeTypes.VIDEO_MP2T -> "MPEG-TS"
            else -> ""
        }
        val item = MediaItem.Builder().setUri(ch.url).apply {
            if (mime != null) setMimeType(mime)
        }.build()

        val source = DefaultMediaSourceFactory(http).createMediaSource(item)
        if (startMs > 0) player.setMediaSource(source, startMs) else player.setMediaSource(source)
        player.prepare()
        player.play()
    }

    fun scheduleHide(ms: Long) {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(ms)
            // Duraklatılmışken çubuk ekranda kalsın.
            if (overlay == Overlay.Banner && player.isPlaying) overlay = Overlay.None
        }
    }

    fun showBanner() {
        overlay = Overlay.Banner
        scheduleHide(if (vod) 3_500 else 4_000)
    }

    fun showBubble(text: String) {
        bubble = text
        bubbleJob?.cancel()
        bubbleJob = scope.launch {
            delay(1_200)
            bubble = null
        }
    }

    fun seekTo(target: Long, label: String? = null) {
        if (!vod) return
        val d = knownDuration()
        val t = target.coerceIn(0L, (d - 1_000).coerceAtLeast(0L))
        seekTarget[0] = t
        seekTarget[1] = SystemClock.uptimeMillis()
        player.seekTo(t)
        posMs = t
        showBubble(label ?: WatchProgress.clock(t))
    }

    fun seekBy(delta: Long) {
        if (!vod) return
        val now = SystemClock.uptimeMillis()
        val base = if (seekTarget[0] >= 0 && now - seekTarget[1] < 1_500) seekTarget[0] else player.currentPosition
        val t = base + delta
        seekTo(t, (if (delta < 0) "−" else "+") + "${abs(delta) / 1000} sn · " + WatchProgress.clock(t.coerceAtLeast(0)))
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun switchBy(step: Int) {
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.key == channel.key }
        val next = list[((if (i < 0) 0 else i) + step).mod(list.size)]
        vm.switchTo(next)
    }

    // Kanal/bölüm değiştikçe: öncekinin konumunu kaydet, yenisini (varsa kaldığı yerden) başlat.
    LaunchedEffect(channel.key) {
        saveProgressNow()
        playingRef[0] = channel
        vod = false
        posMs = 0; durMs = 0; bufMs = 0
        retries = 0
        forceHls = false
        status = null
        buffering = true
        prepare(channel, hls = false, startMs = vm.takeStartPosition())
        showBanner()
    }

    // Süre/konum ve düzenli kayıt.
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(500)
            posMs = if (seekTarget[0] >= 0 && SystemClock.uptimeMillis() - seekTarget[1] < 800) seekTarget[0] else player.currentPosition
            durMs = knownDuration()
            bufMs = player.bufferedPosition
            if (++tick % 30 == 0) saveProgressNow() // ~15 sn
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) {
                    status = null
                    retries = 0
                    tech = describe(player, sourceKind)
                    updateVod()
                }
                if (state == Player.STATE_ENDED) {
                    val ch = playingRef[0]
                    val d = knownDuration()
                    if (ch != null && vod && d > 0) vm.updateProgress(ch, d, d) // bitti → listeden çıkar
                    overlay = Overlay.Controls
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) saveProgressNow()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                updateVod()
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                tech = describe(player, sourceKind)
            }

            override fun onPlayerError(error: PlaybackException) {
                val ch = vm.currentChannel ?: return
                val resumeAt = if (vod) player.currentPosition else 0L
                when {
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                    // Uzantısız linkler bazen HLS çıkar: bir kez HLS olarak dene.
                    !forceHls && vm.streamFormat == "auto" && (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) -> {
                        forceHls = true
                        prepare(ch, hls = true, startMs = resumeAt)
                    }
                    retries < 5 -> {
                        retries++
                        status = "Bağlantı koptu, yeniden deneniyor ($retries/5)…"
                        scope.launch {
                            delay(2_000L * retries)
                            if (vm.currentChannel?.key == ch.key) prepare(ch, hls = forceHls, startMs = resumeAt)
                        }
                    }
                    else -> status = errorText(error)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            saveProgressNow()
            player.removeListener(listener)
            player.release()
        }
    }

    // Panel kapanınca odağı köke geri ver ki tuşlar çalışsın.
    LaunchedEffect(overlay) {
        if (overlay == Overlay.None || overlay == Overlay.Banner) {
            runCatching { rootFocus.requestFocus() }
        }
        // Kontroller bir süre dokunulmazsa kendiliğinden kapanır (oynarken).
        if (overlay == Overlay.Controls) {
            lastInput = SystemClock.uptimeMillis()
            while (overlay == Overlay.Controls) {
                delay(1_000)
                if (player.isPlaying && SystemClock.uptimeMillis() - lastInput > 7_000) overlay = Overlay.None
            }
        }
    }

    BackHandler {
        when (overlay) {
            Overlay.None, Overlay.Banner -> vm.exitPlayer()
            Overlay.Tracks -> overlay = Overlay.Controls
            else -> overlay = Overlay.None
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { lastInput = SystemClock.uptimeMillis(); false }
            .focusRequester(rootFocus)
            .onKeyEvent { e ->
                val ne = e.nativeKeyEvent
                if (ne.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                if (overlay != Overlay.None && overlay != Overlay.Banner) return@onKeyEvent false
                val code = ne.keyCode
                when {
                    code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY ||
                        code == KeyEvent.KEYCODE_MEDIA_PAUSE -> { togglePlay(); showBanner(); true }
                    code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchBy(-1); true }
                    code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_UP -> { switchBy(+1); true }
                    code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_INFO -> {
                        hideJob?.cancel(); overlay = Overlay.Controls; true
                    }
                    // Film/dizi: ◀ ▶ sarar (basılı tutunca hızlanır).
                    vod && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND) -> {
                        seekBy(-seekStep(ne.repeatCount)); showBanner(); true
                    }
                    vod && (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) -> {
                        seekBy(seekStep(ne.repeatCount)); showBanner(); true
                    }
                    vod && (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_GUIDE) -> {
                        hideJob?.cancel(); overlay = Overlay.Channels; true
                    }
                    // Canlı: ◀ kanal listesi, ▶ yayın akışı.
                    code == KeyEvent.KEYCODE_DPAD_LEFT -> { hideJob?.cancel(); overlay = Overlay.Channels; true }
                    code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MENU ||
                        code == KeyEvent.KEYCODE_GUIDE -> { hideJob?.cancel(); overlay = Overlay.Guide; true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        hideJob?.cancel()
                        overlay = if (overlay == Overlay.None || overlay == Overlay.Banner) Overlay.Controls else Overlay.None
                    },
                    onDoubleTap = { off ->
                        if (vod) {
                            seekBy(if (off.x < size.width / 2f) -10_000L else 10_000L)
                            if (overlay == Overlay.None) showBanner()
                        }
                    },
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && status == null) {
            CircularProgressIndicator(color = Palette.Accent, modifier = Modifier.align(Alignment.Center).size(48.dp))
        }
        status?.let {
            Text(
                it, color = Palette.Text, fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        val onAction: (String) -> Unit = { action ->
            when (action) {
                "prev" -> switchBy(-1)
                "next" -> switchBy(+1)
                "close" -> overlay = Overlay.None
                "fav" -> vm.toggleFavorite(channel)
                "channels" -> overlay = Overlay.Channels
                "guide" -> overlay = Overlay.Guide
                "tracks" -> overlay = Overlay.Tracks
                "pause" -> togglePlay()
                "back10" -> seekBy(-10_000)
                "fwd10" -> seekBy(10_000)
                "restart" -> seekTo(0, "Baştan")
            }
        }

        when (overlay) {
            Overlay.Banner, Overlay.Controls -> if (vod) {
                VodControls(
                    vm, channel, posMs, durMs, bufMs, isPlaying, bubble,
                    interactive = overlay == Overlay.Controls,
                    onSeekBy = { seekBy(it) },
                    onSeekTo = { seekTo(it) },
                    onScrub = { showBubble(WatchProgress.clock(it)) },
                    onAction = onAction,
                )
            } else {
                LivePanel(
                    vm, channel, tech, isPlaying, interactive = overlay == Overlay.Controls,
                    modifier = Modifier.align(Alignment.BottomCenter), onAction = onAction,
                )
            }
            Overlay.Channels -> ChannelsPanel(vm, list, channel, Modifier.align(Alignment.CenterStart)) {
                vm.switchTo(it)
                overlay = Overlay.None
            }
            Overlay.Guide -> GuidePanel(vm, channel, Modifier.align(Alignment.CenterEnd))
            Overlay.Tracks -> TracksPanel(player, tracksVersion, Modifier.align(Alignment.CenterEnd)) { overlay = Overlay.Controls }
            Overlay.None -> Unit
        }
    }
}

/** Film/dizi kontrolleri: üstte başlık, altta sarma çubuğu, süreler ve düğmeler. */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VodControls(
    vm: AppViewModel,
    channel: Channel,
    posMs: Long,
    durMs: Long,
    bufMs: Long,
    isPlaying: Boolean,
    bubble: String?,
    interactive: Boolean,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onScrub: (Long) -> Unit,
    onAction: (String) -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    if (interactive) LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tv = maxWidth >= 900.dp
        val side = if (tv) 56.dp else 24.dp

        // Üst: kategori, ad, saat
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xD905070A), Color.Transparent)))
                .padding(start = side, end = side, top = if (tv) 36.dp else 16.dp, bottom = if (tv) 70.dp else 40.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(channel.group, color = Palette.Accent, fontSize = if (tv) 15.sp else 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(channel.name, color = Color.White, fontSize = if (tv) 34.sp else 20.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(formatTime(vm.now), color = Color.White, fontSize = if (tv) 24.sp else 16.sp, fontWeight = FontWeight.Bold)
        }

        // Alt: çubuk + süreler + düğmeler
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0xF205070A)))
                .padding(start = side, end = side, top = if (tv) 120.dp else 70.dp, bottom = if (tv) 34.dp else 14.dp),
        ) {
            SeekBar(
                posMs, durMs, bufMs, bubble, focusable = interactive, tv = tv,
                onSeekBy = onSeekBy, onSeekTo = onSeekTo, onScrub = onScrub,
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(WatchProgress.clock(posMs), color = Color.White, fontSize = if (tv) 18.sp else 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                val endsAt = formatTime(System.currentTimeMillis() + (durMs - posMs).coerceAtLeast(0))
                Text("Bitiş $endsAt · ", color = Color(0xFFB7BFCC), fontSize = if (tv) 16.sp else 13.sp)
                Text(WatchProgress.clock(durMs), color = Color.White, fontSize = if (tv) 18.sp else 14.sp, fontWeight = FontWeight.Bold)
            }

            if (interactive) {
                Spacer(Modifier.height(if (tv) 18.dp else 10.dp))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (tv) 22.dp else 12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PillButton("Baştan başla", tv) { onAction("restart") }
                    RoundButton(if (tv) 64.dp else 52.dp, label = "10 saniye geri", glyph = "↺", small = "10") { onAction("back10") }
                    RoundButton(
                        if (tv) 84.dp else 64.dp, label = if (isPlaying) "Duraklat" else "Oynat",
                        glyph = if (isPlaying) "❚❚" else "▶", white = true, modifier = Modifier.focusRequester(playFocus),
                    ) { onAction("pause") }
                    RoundButton(if (tv) 64.dp else 52.dp, label = "10 saniye ileri", glyph = "↻", small = "10") { onAction("fwd10") }
                    PillButton("Ses / Altyazı", tv) { onAction("tracks") }
                }
            }
            Text(
                if (tv) "◀ ▶ 10 sn · basılı tut: hızlı sar · ▲ ▼ önceki/sonraki · OK menü"
                else "Çubuğa dokun ya da sürükle · ekranın sağına/soluna çift dokun: 10 sn",
                color = Color(0xFF9AA3B2), fontSize = if (tv) 14.sp else 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = if (tv) 16.dp else 8.dp),
            )
        }
    }
}

/** Sarma çubuğu: arabelleğe alınmış kısım, izlenen kısım, tutamaç; kumandayla ◀ ▶, dokunarak/sürükleyerek sarılır. */
@Composable
private fun SeekBar(
    posMs: Long,
    durMs: Long,
    bufMs: Long,
    bubble: String?,
    focusable: Boolean,
    tv: Boolean,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onScrub: (Long) -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    var focused by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(-1f) }
    val density = LocalDensity.current
    val frac = if (scrub >= 0f) scrub else if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    val bufFrac = if (durMs > 0) (bufMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    val trackH = if (tv) 8.dp else 5.dp
    val thumb = when {
        focused || scrub >= 0f -> if (tv) 28.dp else 22.dp
        else -> if (tv) 22.dp else 16.dp
    }

    Box(
        Modifier.fillMaxWidth().height(if (tv) 44.dp else 40.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .then(
                if (focusable) Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { e ->
                        val ne = e.nativeKeyEvent
                        if (ne.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                        when (ne.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { onSeekBy(-seekStep(ne.repeatCount)); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { onSeekBy(seekStep(ne.repeatCount)); true }
                            else -> false
                        }
                    }
                    .focusable()
                else Modifier
            )
            .pointerInput(durMs) {
                detectTapGestures { off -> if (durMs > 0) onSeekTo((off.x / widthPx * durMs).toLong()) }
            }
            .pointerInput(durMs) {
                detectHorizontalDragGestures(
                    onDragStart = { off -> scrub = (off.x / widthPx).coerceIn(0f, 1f) },
                    onDragEnd = { if (scrub >= 0f && durMs > 0) onSeekTo((scrub * durMs).toLong()); scrub = -1f },
                    onDragCancel = { scrub = -1f },
                ) { change, _ ->
                    scrub = (change.position.x / widthPx).coerceIn(0f, 1f)
                    if (durMs > 0) onScrub((scrub * durMs).toLong())
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val widthDp = with(density) { widthPx.toDp() }
        Box(Modifier.fillMaxWidth().height(trackH).clip(RoundedCornerShape(4.dp)).background(Color(0x38FFFFFF)))
        Box(Modifier.fillMaxWidth(bufFrac).height(trackH).clip(RoundedCornerShape(4.dp)).background(Color(0x61FFFFFF)))
        Box(Modifier.fillMaxWidth(frac).height(trackH).clip(RoundedCornerShape(4.dp)).background(Palette.Accent))
        Box(
            Modifier.offset(x = widthDp * frac - thumb / 2).size(thumb)
                .then(if (focused) Modifier.border(6.dp, Palette.Accent.copy(alpha = 0.45f), CircleShape) else Modifier)
                .padding(if (focused) 4.dp else 0.dp)
                .background(Color.White, CircleShape),
        )
        if (bubble != null) {
            Text(
                bubble, color = Palette.Bg, fontSize = if (tv) 16.sp else 13.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                modifier = Modifier
                    .offset(x = (widthDp * frac - 70.dp).coerceIn(0.dp, (widthDp - 140.dp).coerceAtLeast(0.dp)), y = if (tv) (-38).dp else (-32).dp)
                    .widthIn(min = 140.dp)
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RoundButton(
    size: Dp,
    label: String,
    glyph: String,
    small: String? = null,
    white: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Focusable(onClick = onClick, shape = CircleShape, focusedScale = 1.1f, modifier = modifier.size(size)) { focused ->
        Box(
            Modifier.size(size)
                .background(if (white) Color.White else Color(0x1FFFFFFF), CircleShape)
                .then(if (focused) Modifier.border(4.dp, Palette.Accent, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                glyph, color = if (white) Palette.Bg else Color.White,
                fontSize = (size.value * (if (small != null) 0.5f else 0.38f)).sp, fontWeight = FontWeight.Bold,
            )
            if (small != null) {
                Text(small, color = Color.White, fontSize = (size.value * 0.17f).sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun PillButton(text: String, tv: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick, shape = RoundedCornerShape(50), focusedScale = 1.05f) { focused ->
        Text(
            text, color = if (focused) Palette.Bg else Color.White, fontSize = if (tv) 16.sp else 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(if (focused) Color.White else Color(0x1FFFFFFF), RoundedCornerShape(50))
                .padding(horizontal = if (tv) 20.dp else 16.dp, vertical = if (tv) 15.dp else 12.dp),
        )
    }
}

/** Canlı yayın paneli: logo, ad + CANLI, programın ilerlemesi, sonraki program, düğmeler. */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LivePanel(
    vm: AppViewModel,
    channel: Channel,
    tech: String,
    isPlaying: Boolean,
    interactive: Boolean,
    modifier: Modifier,
    onAction: (String) -> Unit,
) {
    val now = vm.now
    val cur = vm.epg.current(channel, now)
    val next = vm.epg.next(channel, now)
    val firstButton = remember { FocusRequester() }
    if (interactive) LaunchedEffect(Unit) { runCatching { firstButton.requestFocus() } }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val tv = maxWidth >= 900.dp
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color(0xF205070A)))
                .padding(start = if (tv) 56.dp else 20.dp, end = if (tv) 56.dp else 20.dp, top = if (tv) 120.dp else 60.dp, bottom = if (tv) 36.dp else 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(
                    vm.logoOf(channel), channel.name,
                    Modifier.size(width = if (tv) 112.dp else 84.dp, height = if (tv) 64.dp else 48.dp),
                    padding = 4.dp, initialsSize = 20.sp, background = Color(0x24FFFFFF),
                )
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            channel.name, color = Color.White, fontSize = if (tv) 30.sp else 20.sp, fontWeight = FontWeight.ExtraBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "CANLI", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.background(Palette.Danger, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        if (channel.key in vm.favorites) Text("  ★", color = Palette.Fav, fontSize = 20.sp)
                    }
                    Text("${channel.group}  ·  $tech", color = Color(0xFFB7BFCC), fontSize = if (tv) 15.sp else 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(formatTime(now), color = Color.White, fontSize = if (tv) 24.sp else 16.sp, fontWeight = FontWeight.Bold)
            }

            if (cur != null) {
                Text(cur.title, color = Color.White, fontSize = if (tv) 20.sp else 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                Box(Modifier.fillMaxWidth().height(if (tv) 8.dp else 5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x38FFFFFF))) {
                    Box(Modifier.fillMaxWidth(cur.progressAt(now)).fillMaxHeight().background(Palette.Danger))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(formatTime(cur.startMs), color = Color(0xFFB7BFCC), fontSize = if (tv) 16.sp else 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${formatTime(cur.endMs)} · ${WatchProgress.remainingText(cur.endMs - now)}",
                        color = Color(0xFFB7BFCC), fontSize = if (tv) 16.sp else 13.sp,
                    )
                }
                if (interactive && !cur.description.isNullOrBlank()) {
                    Text(cur.description, color = Color(0xFF9AA3B2), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp))
                }
            } else {
                Text("Yayın akışı bilgisi yok", color = Color(0xFF9AA3B2), fontSize = 16.sp, modifier = Modifier.padding(top = 18.dp))
            }
            next?.let {
                Row(Modifier.padding(top = 10.dp)) {
                    Text("Sonra: ", color = Color(0xFFB7BFCC), fontSize = if (tv) 16.sp else 13.sp)
                    Text("${formatTime(it.startMs)} ${it.title}", color = Color.White, fontSize = if (tv) 16.sp else 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (interactive) {
                Spacer(Modifier.height(20.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiveButton(
                        if (channel.key in vm.favorites) "★ Favoriden çıkar" else "☆ Favorilere ekle", primary = true,
                        modifier = Modifier.focusRequester(firstButton),
                    ) { onAction("fav") }
                    LiveButton("◀ Önceki") { onAction("prev") }
                    LiveButton(if (isPlaying) "❚❚ Duraklat" else "▶ Oynat") { onAction("pause") }
                    LiveButton("Sonraki ▶") { onAction("next") }
                    LiveButton("Kanallar") { onAction("channels") }
                    LiveButton("Yayın akışı") { onAction("guide") }
                    LiveButton("Ses / Altyazı") { onAction("tracks") }
                }
            }
            if (tv) {
                Text(
                    "▲ ▼ kanal değiştir · ◀ kanal listesi · ▶ yayın akışı · OK menü",
                    color = Color(0xFF9AA3B2), fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun LiveButton(text: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(onClick = onClick, shape = RoundedCornerShape(50), focusedScale = 1.05f, modifier = modifier) { focused ->
        Text(
            text, color = if (focused || primary) Palette.Bg else Color.White, fontSize = 16.sp,
            fontWeight = if (primary) FontWeight.ExtraBold else FontWeight.Bold,
            modifier = Modifier
                .background(
                    when {
                        focused -> Palette.Accent
                        primary -> Color.White
                        else -> Color(0x1FFFFFFF)
                    },
                    RoundedCornerShape(50),
                )
                .padding(horizontal = 20.dp, vertical = 13.dp),
        )
    }
}

/** Ses ve altyazı parçası seçimi. */
@OptIn(UnstableApi::class)
@Composable
private fun TracksPanel(player: ExoPlayer, version: Int, modifier: Modifier, onClose: () -> Unit) {
    data class Opt(val label: String, val selected: Boolean, val apply: () -> Unit)

    fun options(type: Int): List<Opt> {
        val out = ArrayList<Opt>()
        val params = player.trackSelectionParameters
        val groups = player.currentTracks.groups.filter { it.type == type }
        var n = 0
        val tr = Locale("tr")
        for (g in groups) {
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(tr) }
                    ?.replaceFirstChar { it.titlecase(tr) }
                var label = f.label ?: lang ?: "Parça $n"
                if (type == C.TRACK_TYPE_AUDIO && f.channelCount >= 6) label += " · 5.1"
                out += Opt(label, g.isTrackSelected(i)) {
                    player.trackSelectionParameters = params.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                }
            }
        }
        if (type == C.TRACK_TYPE_TEXT) {
            val off = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || out.none { it.selected }
            out.add(0, Opt("Kapalı", off) {
                player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            })
        }
        return out
    }

    val audio = remember(version) { options(C.TRACK_TYPE_AUDIO) }
    val text = remember(version) { options(C.TRACK_TYPE_TEXT) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { focus.requestFocus() } }

    Column(
        modifier.fillMaxWidth(0.9f).widthIn(max = 420.dp).fillMaxHeight()
            .background(Color(0xE6101318)).padding(top = 24.dp),
    ) {
        Text("Ses / Altyazı", color = Palette.Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp))
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text("Ses", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(8.dp)) }
            if (audio.isEmpty()) item { Text("Tek ses parçası var", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(8.dp)) }
            itemsIndexed(audio) { i, o -> TrackRow(o.label, o.selected, if (i == 0) Modifier.focusRequester(focus) else Modifier) { o.apply(); onClose() } }
            item { Text("Altyazı", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)) }
            itemsIndexed(text) { i, o ->
                TrackRow(o.label, o.selected, if (audio.isEmpty() && i == 0) Modifier.focusRequester(focus) else Modifier) { o.apply(); onClose() }
            }
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Focusable(onClick = onClick, selected = selected, focusedScale = 1.02f, modifier = modifier.fillMaxWidth()) { focused ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected) "✓" else "", color = Palette.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
            Text(label, color = if (focused || selected) Palette.Text else Palette.TextDim, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChannelsPanel(
    vm: AppViewModel,
    list: List<Channel>,
    current: Channel,
    modifier: Modifier,
    onPick: (Channel) -> Unit,
) {
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val idx = list.indexOfFirst { it.key == current.key }.coerceAtLeast(0)
    LaunchedEffect(Unit) {
        state.scrollToItem((idx - 3).coerceAtLeast(0))
        delay(50)
        runCatching { focus.requestFocus() }
    }
    Column(
        modifier.fillMaxWidth(0.9f).widthIn(max = 380.dp).fillMaxHeight().background(Color(0xE6101318)).padding(top = 24.dp)
    ) {
        Text(vm.groupLabel(vm.selectedGroup), color = Palette.Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(list, key = { _, c -> c.key }) { i, ch ->
                val cur = vm.epg.current(ch, vm.now)
                Focusable(
                    onClick = { onPick(ch) },
                    selected = ch.key == current.key,
                    focusedScale = 1.02f,
                    modifier = Modifier.fillMaxWidth().then(if (i == idx) Modifier.focusRequester(focus) else Modifier),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.width(32.dp))
                        ChannelLogo(
                            vm.logoOf(ch), ch.name, Modifier.size(width = 52.dp, height = 32.dp),
                            padding = 2.dp, initialsSize = 12.sp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, color = Palette.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (cur != null) Text(cur.title, color = Palette.TextDim, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (ch.key in vm.favorites) Text("★", color = Palette.Fav, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidePanel(vm: AppViewModel, channel: Channel, modifier: Modifier) {
    val now = vm.now
    val progs = vm.epg.programmes(channel)
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val curIdx = progs.indexOfFirst { it.isLiveAt(now) }.let { if (it < 0) progs.indexOfFirst { p -> p.startMs > now } else it }
    LaunchedEffect(Unit) {
        if (curIdx >= 0) state.scrollToItem((curIdx - 2).coerceAtLeast(0))
        delay(50)
        runCatching { focus.requestFocus() }
    }
    Column(
        modifier.fillMaxWidth(0.9f).widthIn(max = 460.dp).fillMaxHeight().background(Color(0xE6101318)).padding(top = 24.dp)
    ) {
        Text("Yayın akışı · ${channel.name}", color = Palette.Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))
        if (progs.isEmpty()) {
            Text(
                if (vm.epgLoading) "Yükleniyor…" else "Bu kanal için yayın akışı yok.",
                color = Palette.TextDim, modifier = Modifier.padding(24.dp).focusRequester(focus).focusable(),
            )
            return@Column
        }
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(progs, key = { _, p -> p.startMs }) { i, p ->
                val live = p.isLiveAt(now)
                val past = p.endMs <= now
                Focusable(
                    onClick = {},
                    selected = live,
                    focusedScale = 1.02f,
                    modifier = Modifier.fillMaxWidth().then(if (i == curIdx.coerceAtLeast(0)) Modifier.focusRequester(focus) else Modifier),
                ) { focused ->
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row {
                            Text(formatTime(p.startMs), color = if (live) Palette.Accent else Palette.TextDim,
                                fontSize = 14.sp, modifier = Modifier.width(56.dp))
                            Text(p.title, color = if (past) Palette.TextDim else Palette.Text, fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (focused && !p.description.isNullOrBlank()) {
                            Text(p.description, color = Palette.TextDim, fontSize = 13.sp, maxLines = 4,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 56.dp, top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun describe(player: ExoPlayer, forcedKind: String): String {
    val kind = when {
        player.currentManifest is HlsManifest -> "HLS"
        forcedKind.isNotEmpty() -> forcedKind
        else -> "Akış"
    }
    val parts = mutableListOf("ExoPlayer", kind)
    val f = player.videoFormat
    if (f != null && f.width > 0 && f.height > 0) parts += "${f.width}×${f.height}"
    when (f?.sampleMimeType) {
        MimeTypes.VIDEO_H264 -> parts += "H.264"
        MimeTypes.VIDEO_H265 -> parts += "H.265"
        MimeTypes.VIDEO_MPEG2 -> parts += "MPEG-2"
        MimeTypes.VIDEO_AV1 -> parts += "AV1"
    }
    return parts.joinToString(" · ")
}

private fun errorText(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Sunucuya bağlanılamadı. İnternet bağlantını kontrol et."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Sunucu yayını reddetti (HTTP hatası). Abonelik veya link geçersiz olabilir."
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Yayın bulunamadı."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Bu yayın biçimi cihazda desteklenmiyor."
    else -> "Oynatılamadı: ${e.errorCodeName}"
}
