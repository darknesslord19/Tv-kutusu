package com.tvkutu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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

@Composable
fun SetupScreen(vm: AppViewModel) {
    var playlist by rememberSaveable { mutableStateOf(vm.repo.playlistUrl) }
    var epg by rememberSaveable { mutableStateOf(vm.repo.epgUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val firstField = remember { FocusRequester() }

    BackHandler(enabled = vm.setupFromSettings) { vm.closeSettings() }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Accent,
        unfocusedBorderColor = Palette.SurfaceHigh,
        focusedTextColor = Palette.Text,
        unfocusedTextColor = Palette.Text,
        focusedLabelColor = Palette.Accent,
        unfocusedLabelColor = Palette.TextDim,
        cursorColor = Palette.Accent,
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 64.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            Text("TvKutu", color = Palette.Accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (vm.setupFromSettings) "Ayarlar" else "Başlamak için IPTV listeni ekle",
                color = Palette.Text, fontSize = 20.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "TvKutu yalnızca bir oynatıcıdır; kanal içermez. Yasal olarak abone olduğun sağlayıcının m3u/m3u8 linkini gir.",
                color = Palette.TextDim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = playlist,
                onValueChange = { playlist = it; error = null },
                label = { Text("Playlist adresi (m3u / m3u8)") },
                placeholder = { Text("https://…/playlist.m3u") },
                singleLine = true,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth().focusRequester(firstField),
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = epg,
                onValueChange = { epg = it },
                label = { Text("Yayın akışı / EPG adresi (isteğe bağlı)") },
                placeholder = { Text("Boş bırakılırsa listedeki url-tvg kullanılır") },
                singleLine = true,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Palette.Danger, fontSize = 14.sp)
            }
            vm.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Palette.Danger, fontSize = 14.sp)
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton("Kaydet ve yükle", primary = true, onClick = {
                    val url = playlist.trim()
                    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                        error = "Adres http:// veya https:// ile başlamalı"
                    } else {
                        vm.consumeMessage()
                        vm.saveSetup(url, epg.trim())
                    }
                })
                if (vm.setupFromSettings) TvButton("Vazgeç", onClick = { vm.closeSettings() })
            }
        }
    }
}
