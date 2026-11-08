package com.tvkutu.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Bg = Color(0xFF0E1116)
    val Surface = Color(0xFF181C23)
    val SurfaceHigh = Color(0xFF232833)
    val Accent = Color(0xFF3DDC97)
    val Text = Color(0xFFECEFF4)
    val TextDim = Color(0xFF9AA3B2)
    val Danger = Color(0xFFFF6B6B)
    val Fav = Color(0xFFFFC857)
}

@Composable
fun TvKutuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.Bg,
            background = Palette.Bg,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            onBackground = Palette.Text,
        ),
        content = content,
    )
}

/**
 * Kumandayla odaklanabilen kart: odakta büyür, vurgu çerçevesi alır.
 * Seçim (OK/Enter) için Compose'un clickable'ı DPAD_CENTER'ı zaten tanır.
 */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    focusedScale: Float = 1.05f,
    selected: Boolean = false,
    onFocus: (() -> Unit)? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "scale")
    val bg = when {
        focused -> Palette.SurfaceHigh
        selected -> Palette.Surface
        else -> Palette.Surface.copy(alpha = 0.6f)
    }
    Box(
        modifier
            .scale(scale)
            .clip(shape)
            .background(bg)
            .border(
                width = 2.dp,
                color = if (focused) Palette.Accent else if (selected) Palette.Accent.copy(alpha = 0.35f) else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
    ) {
        content(focused)
    }
}

@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    Focusable(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(8.dp)) { focused ->
        Text(
            text,
            color = if (focused) Palette.Accent else if (primary) Palette.Accent else Palette.Text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

fun formatTime(ms: Long): String {
    val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return f.format(java.util.Date(ms))
}

fun formatDateTime(ms: Long): String {
    val f = java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale("tr"))
    return f.format(java.util.Date(ms))
}
