package com.tvkutu.app.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.tvkutu.app.AppViewModel
import com.tvkutu.app.data.Channel
import com.tvkutu.app.data.Repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Overlay { None, Banner, Info, Channels, Guide }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channel = vm.currentChannel ?: return

    // Kanal değiştirme, açıldığı kategori içinde yapılır.
    val list = remember(vm.selectedGroup, vm.playlist, vm.favorites) {
        vm.channelsIn(vm.selectedGroup).ifEmpty { vm.channelsIn(AppViewModel.ALL) }
    }

    var overlay by remember { mutableStateOf(Overlay.Banner) }
    var status by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var retries by remember { mutableIntStateOf(0) }
    var forceHls by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<Job?>(null) }

    val rootFocus = remember { FocusRequester() }

    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }

    fun prepare(ch: Channel, hls: Boolean) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ch.userAgent ?: Repository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        ch.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val item = MediaItem.Builder().setUri(ch.url).apply {
            if (hls || ch.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()

        val source = DefaultMediaSourceFactory(http).createMediaSource(item)
        player.setMediaSource(source)
        player.prepare()
        player.play()
    }

    fun showBanner() {
        hideJob?.cancel()
        overlay = Overlay.Banner
        hideJob = scope.launch {
            delay(4_000)
            if (overlay == Overlay.Banner) overlay = Overlay.None
        }
    }

    fun switchBy(step: Int) {
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.key == channel.key }
        val next = list[((if (i < 0) 0 else i) + step).mod(list.size)]
        vm.switchTo(next)
    }

    // Kanal değiştikçe yeniden başlat.
    LaunchedEffect(channel.key) {
        retries = 0
        forceHls = false
        status = null
        buffering = true
        prepare(channel, hls = false)
        showBanner()
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) {
                    status = null
                    retries = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val ch = vm.currentChannel ?: return
                when {
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                    // Uzantısız linkler bazen HLS çıkar: bir kez HLS olarak dene.
                    !forceHls && (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) -> {
                        forceHls = true
                        prepare(ch, hls = true)
                    }
                    retries < 5 -> {
                        retries++
                        status = "Bağlantı koptu, yeniden deneniyor ($retries/5)…"
                        scope.launch {
                            delay(2_000L * retries)
                            if (vm.currentChannel?.key == ch.key) prepare(ch, hls = forceHls)
                        }
                    }
                    else -> status = errorText(error)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Panel kapanınca odağı köke geri ver ki tuşlar çalışsın.
    LaunchedEffect(overlay) {
        if (overlay == Overlay.None || overlay == Overlay.Banner) {
            runCatching { rootFocus.requestFocus() }
        }
    }

    BackHandler {
        if (overlay == Overlay.None || overlay == Overlay.Banner) vm.exitPlayer()
        else overlay = Overlay.None
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onKeyEvent { e ->
                val ne = e.nativeKeyEvent
                if (ne.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                if (overlay != Overlay.None && overlay != Overlay.Banner) return@onKeyEvent false
                when (ne.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchBy(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> { switchBy(+1); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_INFO -> { hideJob?.cancel(); overlay = Overlay.Info; true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { hideJob?.cancel(); overlay = Overlay.Channels; true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_GUIDE -> { hideJob?.cancel(); overlay = Overlay.Guide; true }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (player.isPlaying) player.pause() else player.play(); true
                    }
                    else -> false
                }
            }
            .focusable()
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

        when (overlay) {
            Overlay.Banner -> InfoPanel(vm, channel, interactive = false, onAction = {}, modifier = Modifier.align(Alignment.BottomCenter))
            Overlay.Info -> InfoPanel(vm, channel, interactive = true, modifier = Modifier.align(Alignment.BottomCenter)) { action ->
                when (action) {
                    "fav" -> vm.toggleFavorite(channel)
                    "channels" -> overlay = Overlay.Channels
                    "guide" -> overlay = Overlay.Guide
                    "pause" -> if (player.isPlaying) player.pause() else player.play()
                }
            }
            Overlay.Channels -> ChannelsPanel(vm, list, channel, Modifier.align(Alignment.CenterStart)) {
                vm.switchTo(it)
                overlay = Overlay.None
            }
            Overlay.Guide -> GuidePanel(vm, channel, Modifier.align(Alignment.CenterEnd))
            Overlay.None -> Unit
        }
    }
}

@Composable
private fun InfoPanel(
    vm: AppViewModel,
    channel: Channel,
    interactive: Boolean,
    modifier: Modifier,
    onAction: (String) -> Unit,
) {
    val now = vm.now
    val cur = vm.epg.current(channel, now)
    val next = vm.epg.next(channel, now)
    val firstButton = remember { FocusRequester() }
    if (interactive) LaunchedEffect(Unit) { runCatching { firstButton.requestFocus() } }

    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
            .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (channel.logo != null) {
                AsyncImage(
                    model = channel.logo, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 96.dp, height = 56.dp),
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.name, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (channel.key in vm.favorites) {
                        Text("  ★", color = Palette.Fav, fontSize = 22.sp)
                    }
                }
                Text(channel.group, color = Palette.TextDim, fontSize = 14.sp)
            }
            Text(formatTime(now), color = Palette.Text, fontSize = 22.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (cur != null) {
            Text(
                "${formatTime(cur.startMs)}–${formatTime(cur.endMs)}  ${cur.title}",
                color = Palette.Text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { cur.progressAt(now) },
                color = Palette.Accent, trackColor = Color(0x55FFFFFF),
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
            if (interactive && !cur.description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(cur.description, color = Palette.TextDim, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text("Yayın akışı bilgisi yok", color = Palette.TextDim, fontSize = 16.sp)
        }
        next?.let {
            Spacer(Modifier.height(6.dp))
            Text("Sonra: ${formatTime(it.startMs)}  ${it.title}", color = Palette.TextDim, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (interactive) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    if (channel.key in vm.favorites) "★ Favoriden çıkar" else "☆ Favorilere ekle",
                    onClick = { onAction("fav") },
                    modifier = Modifier.focusRequester(firstButton),
                )
                TvButton("Duraklat / Oynat", onClick = { onAction("pause") })
                TvButton("Kanallar", onClick = { onAction("channels") })
                TvButton("Yayın akışı", onClick = { onAction("guide") })
            }
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
        modifier.width(380.dp).fillMaxHeight().background(Color(0xE6101318)).padding(top = 24.dp)
    ) {
        Text(vm.selectedGroup, color = Palette.Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
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
                        Text("${i + 1}", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.width(36.dp))
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
        modifier.width(460.dp).fillMaxHeight().background(Color(0xE6101318)).padding(top = 24.dp)
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
