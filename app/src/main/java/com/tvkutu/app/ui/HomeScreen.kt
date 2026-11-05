package com.tvkutu.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvkutu.app.AppViewModel
import com.tvkutu.app.data.Channel
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(vm: AppViewModel) {
    val playlist = vm.playlist

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Bg)) {
        val narrow = maxWidth < 600.dp
        Column(Modifier.fillMaxSize()) {
            // Üst çubuk
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (narrow) 16.dp else 32.dp, vertical = if (narrow) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("TvKutu", color = Palette.Accent, fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold)
                if (!narrow) {
                    Spacer(Modifier.width(16.dp))
                    StatusText(vm, Modifier.weight(1f, fill = false))
                }
                Spacer(Modifier.weight(1f))
                TvButton("Yenile", onClick = { vm.refresh() })
                Spacer(Modifier.width(if (narrow) 8.dp else 12.dp))
                TvButton("Ayarlar", onClick = { vm.openSettings() })
                if (!narrow) {
                    Spacer(Modifier.width(20.dp))
                    Text(formatTime(vm.now), color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (narrow) StatusText(vm, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp))

            when {
                playlist == null && vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Palette.Accent)
                        Spacer(Modifier.height(12.dp))
                        Text("Liste yükleniyor…", color = Palette.TextDim)
                    }
                }
                playlist == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(vm.message ?: "Liste yok", color = Palette.TextDim)
                        Spacer(Modifier.height(12.dp))
                        TvButton("Ayarlar", onClick = { vm.openSettings() })
                    }
                }
                narrow -> Column(Modifier.fillMaxSize()) {
                    GroupChips(vm)
                    Content(vm, narrow = true, modifier = Modifier.weight(1f).fillMaxWidth())
                }
                else -> Row(Modifier.fillMaxSize()) {
                    GroupList(vm, Modifier.width(250.dp).fillMaxHeight())
                    Content(vm, narrow = false, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun StatusText(vm: AppViewModel, modifier: Modifier) {
    val text = if (vm.epgLoading) "Yayın akışı yükleniyor…" else vm.message
    if (text != null) {
        Text(text, color = Palette.TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
}

/** Dar ekranda (telefon dikey) kategoriler yatay kaydırılan çipler olarak. */
@Composable
private fun GroupChips(vm: AppViewModel) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(vm.groups, key = { it.id }) { g ->
            val sel = g.id == vm.selectedGroup
            Focusable(onClick = { vm.selectedGroup = g.id }, selected = sel || g.isSource, shape = RoundedCornerShape(50), focusedScale = 1.03f) { focused ->
                Text(
                    if (g.isSource) "▸ ${g.label}" else g.label,
                    color = when {
                        g.isSource -> Palette.Accent
                        sel || focused -> Palette.Text
                        else -> Palette.TextDim
                    },
                    fontSize = 14.sp,
                    fontWeight = if (sel || g.isSource) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupList(vm: AppViewModel, modifier: Modifier) {
    val groups = vm.groups
    val multi = groups.any { it.isSource }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(groups, key = { it.id }) { g ->
            val sel = g.id == vm.selectedGroup
            Column {
            if (g.isSource) Spacer(Modifier.height(10.dp))
            Focusable(
                onClick = { vm.selectedGroup = g.id },
                onFocus = { vm.selectedGroup = g.id },
                selected = sel,
                focusedScale = 1.02f,
                modifier = Modifier.fillMaxWidth()
                    .padding(start = if (multi && !g.isSource && g.id.startsWith("grp:")) 12.dp else 0.dp),
            ) { focused ->
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        g.label,
                        color = when {
                            g.isSource -> Palette.Accent
                            focused || sel -> Palette.Text
                            else -> Palette.TextDim
                        },
                        fontSize = if (g.isSource) 16.sp else 15.sp,
                        fontWeight = if (g.isSource) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${g.count}", color = Palette.TextDim, fontSize = 13.sp)
                }
            }
            }
        }
    }
}

/** Seçili kategoriye göre: "Tüm kanallar"/liste başlığında kategori satırları, aksi halde ızgara. */
@Composable
private fun Content(vm: AppViewModel, narrow: Boolean, modifier: Modifier) {
    if (vm.showsRows(vm.selectedGroup)) CategoryRows(vm, narrow, modifier)
    else ChannelGrid(vm, narrow, modifier)
}

@Composable
private fun CategoryRows(vm: AppViewModel, narrow: Boolean, modifier: Modifier) {
    val rows = vm.rowGroups(vm.selectedGroup)
    val pad = if (narrow) 16.dp else 12.dp
    val listState = rememberLazyListState()
    LaunchedEffect(vm.selectedGroup) { listState.scrollToItem(0) }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
        items(rows, key = { it.id }) { g ->
            val chans = remember(g.id, vm.playlist, vm.favorites) { vm.channelsIn(g.id).take(ROW_LIMIT) }
            val poster = vm.isPosterGroup(g.id)
            Column(Modifier.padding(top = 18.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = pad, end = if (narrow) 16.dp else 32.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        vm.groupLabel(g.id), color = Palette.Text, fontSize = if (narrow) 17.sp else 19.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TvButton("Tümü · ${g.count}", onClick = { vm.selectedGroup = g.id })
                }
                LazyRow(
                    contentPadding = PaddingValues(start = pad, end = if (narrow) 16.dp else 32.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (narrow) 10.dp else 16.dp),
                ) {
                    items(chans, key = { it.key }) { ch ->
                        if (poster) {
                            PosterCard(
                                vm, ch, tv = !narrow, titleBelow = vm.posterTitle == "below", inRow = true,
                                modifier = Modifier.width(if (narrow) 108.dp else 150.dp),
                            )
                        } else {
                            LogoCard(vm, ch, Modifier.width(if (narrow) 150.dp else 200.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelGrid(vm: AppViewModel, narrow: Boolean, modifier: Modifier) {
    val channels = vm.channelsIn(vm.selectedGroup)
    val poster = vm.isPosterGroup(vm.selectedGroup)
    val gridState = rememberLazyGridState()
    val lastKey = vm.currentChannel?.key ?: vm.repo.lastChannelKey
    val returnFocus = remember { FocusRequester() }

    val firstRun = remember { booleanArrayOf(true) }

    LaunchedEffect(vm.selectedGroup) {
        if (firstRun[0]) {
            // Açılışta / oynatıcıdan dönünce son izlenen kanala odaklan.
            firstRun[0] = false
            val idx = channels.indexOfFirst { it.key == lastKey }
            if (idx >= 0) {
                gridState.scrollToItem(idx)
                delay(80)
                runCatching { returnFocus.requestFocus() }
            }
        } else {
            // Kategori değişince listenin başına dön (odağı kenar çubuğunda bırak).
            gridState.scrollToItem(0)
        }
    }

    val cell = when {
        poster && narrow -> 100.dp
        poster -> 140.dp
        narrow -> 150.dp
        else -> 190.dp
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cell),
        state = gridState,
        modifier = modifier,
        contentPadding = if (narrow) PaddingValues(16.dp) else PaddingValues(start = 12.dp, end = 32.dp, top = 12.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(if (narrow) 10.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(if (narrow) 12.dp else 22.dp),
    ) {
        itemsIndexed(channels, key = { _, c -> c.key }) { _, ch ->
            val m = if (ch.key == lastKey) Modifier.focusRequester(returnFocus) else Modifier
            if (poster) {
                PosterCard(vm, ch, tv = !narrow, titleBelow = vm.posterTitle == "below", inRow = false, modifier = m)
            } else {
                LogoCard(vm, ch, m)
            }
        }
    }
}

/**
 * Afiş kartı. Telefonda ad afişin alt kısmında koyu şeritte durur; TV'de şerit yalnızca
 * kumanda kartın üzerindeyken görünür ve kart biraz büyür. "Altta" seçeneğinde ad afişin altında.
 */
@Composable
private fun PosterCard(
    vm: AppViewModel,
    ch: Channel,
    tv: Boolean,
    titleBelow: Boolean,
    inRow: Boolean,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) (if (tv) 1.08f else 1.04f) else 1f, label = "poster")
    val shape = RoundedCornerShape(10.dp)
    val image = vm.logoOf(ch)
    val showOverlay = !titleBelow && !inRow && (!tv || focused) || (!titleBelow && inRow && focused)
    Column(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.play(ch) },
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .scale(scale)
                .shadow(if (focused) 12.dp else 0.dp, shape)
                .then(if (focused) Modifier.border(3.dp, Palette.Accent, shape) else Modifier)
                .clip(shape),
        ) {
            PosterArt(image, ch.name, Modifier.fillMaxSize(), onAspect = { r -> image?.let { vm.reportAspect(it, r) } })
            if (ch.key in vm.favorites) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                        .background(Color(0x8C000000), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("★", color = Palette.Fav, fontSize = 12.sp) }
            }
            if (showOverlay) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2080A0D))))
                        .padding(start = 8.dp, end = 8.dp, top = 26.dp, bottom = 8.dp),
                ) {
                    Text(
                        ch.name, color = Color.White, fontSize = if (tv) 14.sp else 12.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = if (tv) 17.sp else 15.sp,
                    )
                    if (tv && focused) {
                        Text("OK ile oynat", color = Color(0xFFB7BFCC), fontSize = 12.sp)
                    }
                }
            }
        }
        if (titleBelow) {
            Spacer(Modifier.height(6.dp))
            Text(
                ch.name, color = if (focused) Palette.Text else Palette.Text.copy(alpha = 0.9f),
                fontSize = if (tv) 14.sp else 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = if (tv) 17.sp else 15.sp,
            )
        }
    }
}

/** Canlı TV kartı: logo, kanal adı, şu anki program ve ilerleme. */
@Composable
private fun LogoCard(vm: AppViewModel, ch: Channel, modifier: Modifier) {
    val now = vm.now
    val cur = vm.epg.current(ch, now)
    val logo = vm.logoOf(ch)
    Focusable(onClick = { vm.play(ch) }, modifier = modifier.fillMaxWidth()) { focused ->
        Column(Modifier.padding(10.dp)) {
            Box(Modifier.fillMaxWidth().height(84.dp)) {
                ChannelLogo(logo, ch.name, Modifier.fillMaxSize(), onAspect = { r -> logo?.let { vm.reportAspect(it, r) } })
                if (ch.key in vm.favorites) {
                    Text("★", color = Palette.Fav, fontSize = 16.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                ch.name,
                color = if (focused) Palette.Text else Palette.Text.copy(alpha = 0.9f),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                cur?.title ?: " ",
                color = Palette.TextDim, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { cur?.progressAt(now) ?: 0f },
                color = Palette.Accent,
                trackColor = Palette.SurfaceHigh,
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

private const val ROW_LIMIT = 30
