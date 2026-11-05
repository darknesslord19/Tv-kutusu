package com.tvkutu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.tvkutu.app.AppViewModel
import com.tvkutu.app.data.Source

/** TV'de "Hesap Seç": kayıtlı hesaplar renkli kutucuklar olarak; sağ altta telefondan yönetme karekodu. */
@Composable
fun AccountsScreen(vm: AppViewModel) {
    BackHandler(enabled = vm.activeSource != null) { vm.closeAccounts() }
    val accounts = vm.sources
    val activeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { activeFocus.requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Bg)) {
        val wide = maxWidth >= 720.dp
        val tile = if (wide) 200.dp else 148.dp

        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (wide) 48.dp else 20.dp, vertical = if (wide) 28.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("TvKutu", color = Palette.Accent, fontSize = if (wide) 24.sp else 20.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.weight(1f))
            if (vm.checking) Text("Hesaplar kontrol ediliyor…", color = Palette.TextDim, fontSize = 14.sp)
            Spacer(Modifier.width(16.dp))
            TvButton("Ayarlar", onClick = { vm.openSettings() })
            if (wide) {
                Spacer(Modifier.width(20.dp))
                Text(formatTime(vm.now), color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }

        Column(
            Modifier.fillMaxSize().padding(top = if (wide) 40.dp else 70.dp, bottom = if (wide) 150.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Hesap Seç", color = Palette.Text, fontSize = if (wide) 44.sp else 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                if (wide) "Kumandayla seç, OK'a bas — ya da telefondan seç" else "Dokunduğun hesap açılır",
                color = Palette.TextDim, fontSize = if (wide) 18.sp else 14.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(if (wide) 44.dp else 28.dp))

            val items: List<Source?> = accounts + listOf(null) // null = "Yeni hesap"
            val tileContent: @Composable (Source?) -> Unit = { s ->
                if (s == null) {
                    AddTile(tile) { vm.openQrSetup() }
                } else {
                    val isActive = s.id == vm.activeId
                    AccountTile(
                        vm, s, tile, isActive,
                        modifier = if (isActive || (vm.activeId == null && s == accounts.first())) Modifier.focusRequester(activeFocus) else Modifier,
                    )
                }
            }
            if (wide) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(items, key = { it?.id ?: "+" }) { tileContent(it) }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    modifier = Modifier.widthIn(max = 380.dp),
                ) {
                    items(items, key = { it?.id ?: "+" }) { Box(contentAlignment = Alignment.Center) { tileContent(it) } }
                }
            }
        }

        // Telefondan yönet kartı
        val url = vm.remoteUrl
        if (wide && url != null) {
            val qr = remember(url) { qrBitmap(url) }
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 36.dp)
                    .clip(RoundedCornerShape(18.dp)).background(Palette.Surface)
                    .padding(start = 14.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QrCard(qr, 104.dp, padding = 8.dp)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.widthIn(max = 230.dp)) {
                    Text("Telefondan yönet", color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Karekodu okut: hesap seç, ekle ya da düzenle. Seçtiğin hesap burada hemen açılır.",
                        color = Palette.TextDim, fontSize = 14.sp, lineHeight = 19.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountTile(vm: AppViewModel, s: Source, size: Dp, isActive: Boolean, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "tile")
    val shape = RoundedCornerShape(size * 0.17f)
    val color = parseHex(s.color)
    val (meta, metaColor) = when {
        vm.checking && s.checkedMs == 0L -> "Kontrol ediliyor…" to Palette.TextDim
        s.error.isNotEmpty() -> "● Bağlanamadı" to Color(0xFFFF8A8A)
        isActive -> "● Son açılan" to Palette.Accent
        s.count > 0 -> "%,d kanal".format(s.count).replace(',', '.') to Palette.TextDim
        else -> "Yeni" to Palette.TextDim
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.zIndex(if (focused) 1f else 0f)) {
        Box(
            modifier
                .size(size)
                .scale(scale)
                .shadow(if (focused) 18.dp else 0.dp, shape)
                .clip(shape)
                .background(color)
                .border(if (focused) 4.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
                .onFocusChanged { focused = it.isFocused }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.openAccount(s.id) },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(size * 0.6f).align(Alignment.TopEnd).offset(x = size * 0.09f, y = size * 0.07f)
                .background(Color.White.copy(alpha = 0.08f), CircleShape))
            Box(Modifier.size(size * 0.3f).align(Alignment.BottomStart).offset(x = size * 0.12f, y = -size * 0.17f)
                .background(Color.White.copy(alpha = 0.07f), CircleShape))
            if (s.locked && vm.hasPin) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(12.dp).size(30.dp).background(Color(0x73000000), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("🔒", fontSize = 14.sp) }
            }
            Text(
                s.name.uppercase(), color = Color.White, fontSize = if (size > 160.dp) 26.sp else 20.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = if (size > 160.dp) 30.sp else 23.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(meta, color = metaColor, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun AddTile(size: Dp, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "add")
    val shape = RoundedCornerShape(size * 0.17f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).scale(scale).clip(shape).background(Palette.SurfaceHigh)
                .border(if (focused) 4.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
                .onFocusChanged { focused = it.isFocused }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text("＋", color = Palette.Text, fontSize = if (size > 160.dp) 56.sp else 44.sp, fontWeight = FontWeight.Light)
        }
        Spacer(Modifier.height(14.dp))
        Text("Yeni hesap", color = Palette.TextDim, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

fun parseHex(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF2E3440))
