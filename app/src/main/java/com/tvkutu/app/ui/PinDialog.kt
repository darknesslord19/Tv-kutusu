package com.tvkutu.app.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tvkutu.app.PinRequest

/**
 * 4 haneli PIN penceresi. Kumandanın rakam tuşları ya da ekrandaki tuş takımı ile girilir.
 * [PinRequest.create] ise PIN iki kez sorulur.
 */
@Composable
fun PinDialog(req: PinRequest, onCancel: () -> Unit) {
    var digits by remember(req) { mutableStateOf("") }
    var first by remember(req) { mutableStateOf<String?>(null) }
    var note by remember(req) { mutableStateOf<String?>(null) }
    val firstKey = remember { FocusRequester() }

    fun submit(pin: String) {
        if (!req.create) {
            req.onPin(pin)
            digits = ""
            return
        }
        val f = first
        if (f == null) {
            first = pin; digits = ""; note = null
        } else if (f == pin) {
            req.onPin(pin)
        } else {
            first = null; digits = ""; note = "PIN'ler eşleşmedi, baştan gir"
        }
    }

    fun press(d: Char) {
        if (digits.length >= 4) return
        digits += d
        if (digits.length == 4) submit(digits)
    }

    fun back() { if (digits.isNotEmpty()) digits = digits.dropLast(1) }

    LaunchedEffect(req) { runCatching { firstKey.requestFocus() } }

    val subtitle = note ?: when {
        req.create && first != null -> "Aynı PIN'i bir kez daha gir"
        else -> req.subtitle
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(420.dp)
                .background(Palette.Surface, RoundedCornerShape(24.dp))
                .padding(28.dp)
                .onPreviewKeyEvent { e ->
                    val ne = e.nativeKeyEvent
                    if (ne.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (ne.keyCode) {
                        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { press('0' + (ne.keyCode - KeyEvent.KEYCODE_0)); true }
                        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { press('0' + (ne.keyCode - KeyEvent.KEYCODE_NUMPAD_0)); true }
                        KeyEvent.KEYCODE_DEL -> { back(); true }
                        else -> false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(req.title, color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle, color = if (note != null || subtitle.contains("yanlış")) Color(0xFFFF8A8A) else Palette.TextDim,
                fontSize = 15.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(4) { i ->
                    val filled = i < digits.length
                    Box(
                        Modifier.size(18.dp)
                            .background(if (filled) Palette.Accent else Color.Transparent, CircleShape)
                            .border(2.dp, if (filled) Palette.Accent else Palette.TextDim, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                keys.chunked(3).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { c, k ->
                            val m = if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier
                            Focusable(
                                onClick = {
                                    when (k) {
                                        "⌫" -> back()
                                        "✕" -> onCancel()
                                        else -> press(k[0])
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                focusedScale = 1.06f,
                                modifier = m.size(width = 96.dp, height = 60.dp),
                            ) { focused ->
                                Box(Modifier.size(width = 96.dp, height = 60.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        k,
                                        color = when {
                                            focused -> Palette.Accent
                                            k == "✕" -> Color(0xFFFF8A8A)
                                            else -> Palette.Text
                                        },
                                        fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Kumandanın rakam tuşlarını da kullanabilirsin", color = Palette.TextDim, fontSize = 13.sp)
        }
    }
}
