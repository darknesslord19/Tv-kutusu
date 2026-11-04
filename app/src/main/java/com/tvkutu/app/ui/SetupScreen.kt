package com.tvkutu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvkutu.app.AppViewModel
import com.tvkutu.app.BuildConfig

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(vm: AppViewModel) {
    var playlist by rememberSaveable { mutableStateOf(vm.repo.playlistUrl) }
    var epg by rememberSaveable { mutableStateOf(vm.repo.epgUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val qrFocus = remember { FocusRequester() }
    val firstRun = !vm.setupFromSettings

    BackHandler(enabled = vm.setupFromSettings) { vm.closeSettings() }
    // Kumandayla en kolay yol telefondan kurulum: odağı oraya koy.
    LaunchedEffect(Unit) { runCatching { qrFocus.requestFocus() } }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Accent,
        unfocusedBorderColor = Palette.SurfaceHigh,
        focusedTextColor = Palette.Text,
        unfocusedTextColor = Palette.Text,
        focusedLabelColor = Palette.Accent,
        unfocusedLabelColor = Palette.TextDim,
        focusedPlaceholderColor = Palette.TextDim,
        unfocusedPlaceholderColor = Palette.TextDim,
        cursorColor = Palette.Accent,
    )

    fun save() {
        val url = playlist.trim()
        val e = epg.trim()
        error = when {
            url.isEmpty() -> "Playlist linkini gir ya da telefondan kurulumu kullan."
            !url.startsWith("http://", true) && !url.startsWith("https://", true) ->
                "Playlist linki http:// veya https:// ile başlamalı."
            e.isNotEmpty() && !e.startsWith("http://", true) && !e.startsWith("https://", true) ->
                "EPG linki http:// veya https:// ile başlamalı."
            else -> null
        }
        if (error == null) {
            vm.consumeMessage()
            vm.saveSetup(url, e)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Bg)) {
        val wide = maxWidth >= 600.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 64.dp else 20.dp, vertical = if (wide) 40.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                if (firstRun) {
                    Text("TvKutu", color = Palette.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Hoş geldin", color = Palette.Text, fontSize = if (wide) 34.sp else 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Başlamak için IPTV listeni ekle.", color = Palette.TextDim, fontSize = 16.sp)
                } else {
                    Text("Ayarlar", color = Palette.Text, fontSize = if (wide) 34.sp else 28.sp, fontWeight = FontWeight.Bold)
                }

                // ---- Playlist ve yayın akışı ----
                Section("Playlist ve yayın akışı")
                Text(
                    "Playlist linkini (m3u/m3u8) ve istersen yayın akışı linkini (XMLTV) gir. " +
                        "EPG linki boş kalırsa listedeki url-tvg linki kullanılır.",
                    color = Palette.TextDim, fontSize = 15.sp,
                )
                Spacer(Modifier.height(16.dp))
                Focusable(
                    onClick = { vm.openQrSetup() },
                    shape = RoundedCornerShape(50),
                    focusedScale = 1.03f,
                    selected = true,
                    modifier = Modifier.focusRequester(qrFocus),
                ) { focused ->
                    Row(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📱", fontSize = 20.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Telefondan kur (karekod)",
                            color = if (focused) Palette.Accent else Palette.Text,
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("veya linkleri elle gir:", color = Palette.TextDim, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = playlist,
                    onValueChange = { playlist = it; error = null },
                    label = { Text("Playlist linki (m3u / m3u8)") },
                    placeholder = { Text("https://…/playlist.m3u") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = epg,
                    onValueChange = { epg = it; error = null },
                    label = { Text("EPG linki (xml, xml.gz) — isteğe bağlı") },
                    placeholder = { Text("https://…/epg.xml.gz") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth(),
                )

                (error ?: vm.loadError)?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Palette.Danger, fontSize = 14.sp)
                }

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Kaydet ve yükle", primary = true, onClick = { save() })
                    if (vm.setupFromSettings) TvButton("Vazgeç", onClick = { vm.closeSettings() })
                }

                // ---- Otomatik güncelleme ----
                Section("Otomatik güncelleme")
                Text("Liste ve yayın akışı, uygulama açıkken seçtiğin aralıkla yenilenir.",
                    color = Palette.TextDim, fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    listOf(6 to "6 saatte bir", 12 to "12 saatte bir", 24 to "24 saatte bir", 0 to "Kapalı").forEach { (h, label) ->
                        Chip(label, selected = vm.updateHours == h) { vm.setUpdateInterval(h) }
                    }
                }

                // ---- Hakkında ----
                Section("Hakkında")
                Text("TvKutu ${BuildConfig.VERSION_NAME}", color = Palette.Text, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "TvKutu yalnızca bir oynatıcıdır; kanal, yayın veya abonelik içermez. " +
                        "Yasal olarak abone olduğun sağlayıcının listesini kullan. Hesap, reklam ve analitik yoktur; linkler yalnızca bu cihazda saklanır.",
                    color = Palette.TextDim, fontSize = 13.sp,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(32.dp))
    Text(title.uppercase(), color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = Palette.SurfaceHigh)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick, shape = RoundedCornerShape(50), selected = selected, focusedScale = 1.04f) { focused ->
        Text(
            (if (selected) "✓  " else "") + label,
            color = if (focused || selected) Palette.Text else Palette.TextDim,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
        )
    }
}
