package com.tvkutu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Kanal logosu. Logo yoksa ya da yüklenemezse kanal adının baş harflerini gösterir.
 */
@Composable
fun ChannelLogo(
    logo: String?,
    name: String,
    modifier: Modifier = Modifier,
    padding: Dp = 8.dp,
    initialsSize: TextUnit = 26.sp,
    background: Color = Color(0xFF0B0D11),
) {
    var failed by remember(logo) { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (logo == null || failed) {
            Text(initials(name), color = Palette.TextDim, fontSize = initialsSize, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (logo != null && !failed) {
            AsyncImage(
                model = logo,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

private fun initials(name: String): String {
    val words = name.replace(Regex("""\b(HD|FHD|UHD|SD|4K|TV)\b""", RegexOption.IGNORE_CASE), " ")
        .split(Regex("""[\s\-_.|:]+""")).filter { it.isNotBlank() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        words.size == 1 -> words[0].take(3)
        else -> name.take(2)
    }.uppercase()
}
