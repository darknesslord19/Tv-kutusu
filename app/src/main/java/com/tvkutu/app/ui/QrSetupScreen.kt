package com.tvkutu.app.ui

import android.graphics.Bitmap
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

/** Telefondan yönetme ekranı: kalıcı kumanda adresinin karekodu ve adımlar. */
@Composable
fun QrSetupScreen(vm: AppViewModel) {
    BackHandler { vm.closeQrSetup() }
    val url = vm.remoteUrl

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Bg), contentAlignment = Alignment.Center) {
        val wide = maxWidth >= 600.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 64.dp else 20.dp, vertical = if (wide) 40.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                Text("TELEFONDAN YÖNET", color = Palette.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Hesaplarını telefondan seç, ekle, düzenle", color = Palette.Text,
                    fontSize = if (wide) 30.sp else 24.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(if (wide) 28.dp else 20.dp))
                if (url == null) {
                    ErrorBlock(
                        "TV bir ağa bağlı görünmüyor. Wi‑Fi veya kablo bağlantısını kontrol edip tekrar dene.",
                        onRetry = { vm.startRemote() }, onBack = { vm.closeQrSetup() },
                    )
                } else {
                    val qr = remember(url) { qrBitmap(url) }
                    if (wide) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            QrCard(qr, 300.dp)
                            Spacer(Modifier.width(48.dp))
                            Steps(url, onBack = { vm.closeQrSetup() }, modifier = Modifier.weight(1f))
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            QrCard(qr, 240.dp)
                            Spacer(Modifier.height(24.dp))
                            Steps(url, onBack = { vm.closeQrSetup() }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QrCard(qr: ImageBitmap, size: Dp, padding: Dp = 14.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Image(qr, contentDescription = "Telefondan yönetme karekodu", filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun Steps(url: String, onBack: () -> Unit, modifier: Modifier) {
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
    Column(modifier) {
        Step(1, "Telefonun TV ile aynı Wi‑Fi ağına bağlı olsun.")
        Step(2, "Telefonun kamerasıyla karekodu okut ve açılan linke dokun.")
        Step(3, "\"Hesap Seç\" sayfasında hesaba dokun ya da ＋ ile yenisini ekle. Seçtiğin hesap burada hemen açılır.")
        Spacer(Modifier.height(12.dp))
        Text("Karekod okumazsa telefonun tarayıcısında şu adresi aç:", color = Palette.TextDim, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            url, color = Palette.Text, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(20.dp))
        TvButton("Geri", onClick = onBack, modifier = Modifier.focusRequester(backFocus))
        Spacer(Modifier.height(10.dp))
        Text(
            "Sayfayı telefonunda yer imlerine ekleyebilirsin; adres değişmez. Telefon sayfasında ayrıca kanal arama ve yedekleme var. Sayfa yalnızca evindeki ağda, TvKutu açıkken çalışır.",
            color = Palette.TextDim, fontSize = 12.sp,
        )
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

fun qrBitmap(text: String): ImageBitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8")
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val w = m.width
    val h = m.height
    val pixels = IntArray(w * h) { i -> if (m[i % w, i / w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}
