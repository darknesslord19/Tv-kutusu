package com.tvkutu.app.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.tvkutu.app.AppViewModel
import com.tvkutu.app.data.SetupServer

private sealed interface QrState {
    data object Starting : QrState
    data class Ready(val url: String, val qr: ImageBitmap) : QrState
    data class Error(val text: String) : QrState
}

@Composable
fun QrSetupScreen(vm: AppViewModel) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<QrState>(QrState.Starting) }
    val main = remember { Handler(Looper.getMainLooper()) }

    BackHandler { vm.closeQrSetup() }

    DisposableEffect(attempt) {
        state = QrState.Starting
        var server: SetupServer? = null
        val ip = SetupServer.localIpv4()
        if (ip == null) {
            state = QrState.Error("TV bir ağa bağlı görünmüyor. Wi‑Fi veya kablo bağlantısını kontrol edip tekrar dene.")
        } else {
            try {
                val s = SetupServer(vm.sources.map { it.name to it.url }, vm.repo.epgUrl) { entries, epg ->
                    main.post { vm.applyFromPhone(entries, epg) }
                }
                val port = s.start()
                server = s
                val url = "http://$ip:$port/s/${s.code}"
                state = QrState.Ready(url, qrBitmap(url))
            } catch (e: Exception) {
                state = QrState.Error("Kurulum sayfası başlatılamadı: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        onDispose { server?.stop() }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Palette.Bg),
        contentAlignment = Alignment.Center,
    ) {
        val wide = maxWidth >= 600.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 64.dp else 20.dp, vertical = if (wide) 40.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                Text("TELEFONDAN KURULUM", color = Palette.Accent, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                Text("Linkleri telefonundan gönder", color = Palette.Text,
                    fontSize = if (wide) 30.sp else 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(if (wide) 28.dp else 20.dp))

                when (val st = state) {
                    QrState.Starting -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Palette.Accent)
                    }
                    is QrState.Error -> ErrorBlock(st.text, onRetry = { attempt++ }, onBack = { vm.closeQrSetup() })
                    is QrState.Ready -> if (wide) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            QrCard(st.qr, 300.dp)
                            Spacer(Modifier.width(48.dp))
                            Steps(st.url, onBack = { vm.closeQrSetup() }, modifier = Modifier.weight(1f))
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            QrCard(st.qr, 240.dp)
                            Spacer(Modifier.height(24.dp))
                            Steps(st.url, onBack = { vm.closeQrSetup() }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCard(qr: ImageBitmap, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(qr, contentDescription = "Kurulum karekodu", filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun Steps(url: String, onBack: () -> Unit, modifier: Modifier) {
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
    Column(modifier) {
        Step(1, "Telefonun TV ile aynı Wi‑Fi ağına bağlı olsun.")
        Step(2, "Telefonun kamerasıyla karekodu okut ve açılan linke dokun.")
        Step(3, "Bir ya da birden çok playlist linkini (istersen EPG'yi de) yapıştır, \"Gönder\"e dokun.")
        Spacer(Modifier.height(12.dp))
        Text("Karekod okumazsa telefonun tarayıcısında şu adresi aç:", color = Palette.TextDim, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            url, color = Palette.Text, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = Palette.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Telefon bekleniyor…", color = Palette.TextDim, fontSize = 15.sp)
        }
        Spacer(Modifier.height(20.dp))
        TvButton("Geri", onClick = onBack, modifier = Modifier.focusRequester(backFocus))
        Spacer(Modifier.height(10.dp))
        Text("Sayfa yalnızca evindeki ağda çalışır, tek kullanımlıktır ve gönderimden sonra kapanır.",
            color = Palette.TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(Palette.Accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", color = Palette.Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Palette.Text, fontSize = 17.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ErrorBlock(text: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(text) { runCatching { focus.requestFocus() } }
    Column {
        Text(text, color = Palette.Danger, fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Tekrar dene", onClick = onRetry, primary = true, modifier = Modifier.focusRequester(focus))
            TvButton("Geri", onClick = onBack)
        }
    }
}

private fun qrBitmap(text: String): ImageBitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8")
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val w = m.width
    val h = m.height
    val pixels = IntArray(w * h) { i -> if (m[i % w, i / w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}
