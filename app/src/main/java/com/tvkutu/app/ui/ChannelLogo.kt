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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    onAspect: ((Float) -> Unit)? = null,
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
                onSuccess = { st -> onAspect?.let { cb -> st.painter.intrinsicSize.ratioOrNull()?.let(cb) } },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

/**
 * Film/dizi afişi: görsel kartı tamamen doldurur (kırparak). Görsel yoksa ya da
 * yüklenemezse adı yazan sade bir afiş gösterilir.
 */
@Composable
fun PosterArt(
    image: String?,
    name: String,
    modifier: Modifier = Modifier,
    onAspect: ((Float) -> Unit)? = null,
) {
    var failed by remember(image) { mutableStateOf(false) }
    Box(modifier.background(Color(0xFF1B2029)), contentAlignment = Alignment.Center) {
        if (image == null || failed) {
            Text(
                name, color = Palette.TextDim, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
        if (image != null && !failed) {
            AsyncImage(
                model = image,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                onSuccess = { st -> onAspect?.let { cb -> st.painter.intrinsicSize.ratioOrNull()?.let(cb) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun Size.ratioOrNull(): Float? =
    if (isUnspecified || width <= 0f || height <= 0f || width.isInfinite() || height.isInfinite()) null else width / height

private fun initials(name: String): String {
    val words = name.replace(Regex("""\b(HD|FHD|UHD|SD|4K|TV)\b""", RegexOption.IGNORE_CASE), " ")
        .split(Regex("""[\s\-_.|:]+""")).filter { it.isNotBlank() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        words.size == 1 -> words[0].take(3)
        else -> name.take(2)
    }.uppercase()
}
