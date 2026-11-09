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
import androidx.compose.material3.lightColorScheme
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

/** Bir renk teması. [light] açık temalar için. */
data class AppTheme(
    val id: String,
    val name: String,
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val accent: Color,
    val text: Color,
    val textDim: Color,
    val danger: Color = Color(0xFFFF6B6B),
    val fav: Color = Color(0xFFFFC857),
    val light: Boolean = false,
)

object Themes {
    val all = listOf(
        AppTheme("gece", "Gece", Color(0xFF0E1116), Color(0xFF181C23), Color(0xFF232833), Color(0xFF3DDC97), Color(0xFFECEFF4), Color(0xFF9AA3B2)),
        AppTheme("okyanus", "Okyanus", Color(0xFF0B1420), Color(0xFF13202F), Color(0xFF1C2D40), Color(0xFF4FB3FF), Color(0xFFE8F1FA), Color(0xFF93A6BA)),
        AppTheme("gunbatimi", "Gün batımı", Color(0xFF170F14), Color(0xFF24171F), Color(0xFF33212C), Color(0xFFFF8A5B), Color(0xFFF7ECEF), Color(0xFFB59AA6)),
        AppTheme("mor", "Lavanta", Color(0xFF120E1A), Color(0xFF1D1628), Color(0xFF2A2038), Color(0xFFB58CFF), Color(0xFFF0EAFA), Color(0xFFA49BB8)),
        AppTheme("orman", "Orman", Color(0xFF0C140F), Color(0xFF152019), Color(0xFF1F2D24), Color(0xFF7BD88F), Color(0xFFE9F3EC), Color(0xFF93A99A)),
        AppTheme("sinema", "Sinema", Color(0xFF120B0C), Color(0xFF1E1213), Color(0xFF2B1A1C), Color(0xFFE5484D), Color(0xFFF5EAEA), Color(0xFFB09A9B), danger = Color(0xFFFF9F43)),
        AppTheme("altin", "Altın", Color(0xFF13110B), Color(0xFF1F1B12), Color(0xFF2C2619), Color(0xFFE7B75F), Color(0xFFF6F0E2), Color(0xFFB0A68E)),
        AppTheme("buz", "Buz", Color(0xFF0D1517), Color(0xFF152225), Color(0xFF1E3034), Color(0xFF5ED6D6), Color(0xFFE6F5F5), Color(0xFF8FAAAD)),
        AppTheme("amoled", "Simsiyah", Color(0xFF000000), Color(0xFF0F0F10), Color(0xFF1C1C1F), Color(0xFF00E0A4), Color(0xFFF2F2F2), Color(0xFF9A9A9F)),
        AppTheme(
            "gunduz", "Gündüz (açık)", Color(0xFFF2F3F6), Color(0xFFFFFFFF), Color(0xFFE3E6EB), Color(0xFF0E9F6E),
            Color(0xFF14171C), Color(0xFF5B6472), danger = Color(0xFFD93A3F), fav = Color(0xFFD99A00), light = true,
        ),
    )

    fun byId(id: String?): AppTheme = all.firstOrNull { it.id == id } ?: all[0]
}

/** Uygulama renkleri: seçili temadan okunur; tema değişince ekranlar kendiliğinden yenilenir. */
object Palette {
    var theme by mutableStateOf(Themes.all[0])
    val Bg get() = theme.bg
    val Surface get() = theme.surface
    val SurfaceHigh get() = theme.surfaceHigh
    val Accent get() = theme.accent
    val Text get() = theme.text
    val TextDim get() = theme.textDim
    val Danger get() = theme.danger
    val Fav get() = theme.fav
    /** Açık zemin (beyaz/vurgu) üzerindeki koyu yazı; temadan bağımsız. */
    val Ink = Color(0xFF0E1116)
}

@Composable
fun TvKutuTheme(content: @Composable () -> Unit) {
    val t = Palette.theme
    MaterialTheme(
        colorScheme = if (t.light) lightColorScheme(
            primary = t.accent, onPrimary = Color.White, background = t.bg, surface = t.surface,
            onSurface = t.text, onBackground = t.text,
        ) else darkColorScheme(
            primary = t.accent, onPrimary = Palette.Ink, background = t.bg, surface = t.surface,
            onSurface = t.text, onBackground = t.text,
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
