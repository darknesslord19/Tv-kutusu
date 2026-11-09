package com.tvkutu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tvkutu.app.AppViewModel
import com.tvkutu.app.ResumeRequest
import com.tvkutu.app.data.WatchProgress
import kotlinx.coroutines.delay

/** "Kaldığın yerden devam et?" — 10 sn içinde seçim yapılmazsa kaldığı yerden başlar. */
@Composable
fun ResumeDialog(vm: AppViewModel, req: ResumeRequest) {
    val ch = req.channel
    val p = req.progress
    var countdown by remember(req) { mutableIntStateOf(10) }
    var touched by remember(req) { mutableIntStateOf(0) } // kullanıcı gezindiyse geri sayım durur
    val first = remember { FocusRequester() }

    LaunchedEffect(req) { runCatching { first.requestFocus() } }
    LaunchedEffect(req, touched) {
        if (touched > 1) return@LaunchedEffect
        while (countdown > 0) {
            delay(1_000)
            countdown--
        }
        vm.resumeFromSaved()
    }

    Dialog(onDismissRequest = { vm.dismissResume() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            val wide = maxWidth >= 640.dp
            Row(
                Modifier.widthIn(max = 860.dp)
                    .background(Palette.Surface, RoundedCornerShape(26.dp))
                    .padding(if (wide) 36.dp else 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (wide) {
                    Box(Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp))) {
                        PosterArt(vm.logoOf(ch), ch.name, Modifier.fillMaxSize())
                        ProgressStrip(p.fraction, Modifier.align(Alignment.BottomCenter))
                    }
                    Spacer(Modifier.width(36.dp))
                }
                Column(Modifier.weight(1f, fill = false)) {
                    Text(ch.group, color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(ch.name, color = Palette.Text, fontSize = if (wide) 32.sp else 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "${watchedText(p.posMs)} izledin · ${WatchProgress.remainingText(p.remainingMs)}",
                        color = Palette.TextDim, fontSize = 17.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.SurfaceHigh)) {
                        Box(Modifier.fillMaxWidth(p.fraction).height(8.dp).background(Palette.Accent))
                    }
                    Spacer(Modifier.height(26.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DialogButton(
                            "▶  Kaldığın yerden devam et", trailing = WatchProgress.clock(p.posMs), primary = true,
                            modifier = Modifier.focusRequester(first),
                            onFocus = { touched++ },
                        ) { vm.resumeFromSaved() }
                        DialogButton("↺  Baştan başla", onFocus = { touched++ }) { vm.restartFromBeginning() }
                        DialogButton("✕  Listeden kaldır", onFocus = { touched++ }) { vm.removeFromContinue(ch) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (touched > 1) "Bir seçenek seç" else "$countdown sn içinde seçmezsen kaldığın yerden devam eder",
                        color = Palette.TextDim, fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    primary: Boolean = false,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Focusable(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.03f,
        modifier = modifier.fillMaxWidth().widthIn(min = 300.dp).onFocusChanged { if (it.isFocused) onFocus() },
    ) { focused ->
        Row(
            Modifier.fillMaxWidth()
                .background(if (focused) Palette.Accent else Palette.SurfaceHigh)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text, color = if (focused) Palette.Ink else Palette.Text,
                fontSize = 18.sp, fontWeight = if (primary) FontWeight.ExtraBold else FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(trailing, color = if (focused) Palette.Ink.copy(alpha = 0.7f) else Palette.TextDim, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Kartın altındaki ince ilerleme çizgisi. */
@Composable
fun ProgressStrip(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(5.dp).background(Color(0x80000000))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(5.dp).background(Palette.Accent))
    }
}

private fun watchedText(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(1)
    val h = min / 60
    val m = min % 60
    return when {
        h == 0L -> "$m dk"
        m == 0L -> "$h sa"
        else -> "$h sa $m dk"
    }
}
