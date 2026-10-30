package com.mytv.player

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Video height (px) -> SD / HD / FHD / 4K. Null when unknown. */
fun qualityLabel(height: Int): String? = when {
    height >= 2160 -> "4K"
    height >= 1080 -> "FHD"
    height >= 720 -> "HD"
    height > 0 -> "SD"
    else -> null
}

private val Q_4K = Regex("""(?i)(?<![a-z0-9])(4k|uhd|2160p?)(?![a-z0-9])""")
private val Q_FHD = Regex("""(?i)(?<![a-z0-9])(fhd|1080[pi]?)(?![a-z0-9])""")
private val Q_HD = Regex("""(?i)(?<![a-z0-9])(hd|720p?)(?![a-z0-9])""")
private val Q_SD = Regex("""(?i)(?<![a-z0-9])(sd|480p?|576p?)(?![a-z0-9])""")

/** Best guess from the channel name, used until the real resolution has been seen once. */
fun nameQuality(name: String): String? = when {
    Q_4K.containsMatchIn(name) -> "4K"
    Q_FHD.containsMatchIn(name) -> "FHD"
    Q_HD.containsMatchIn(name) -> "HD"
    Q_SD.containsMatchIn(name) -> "SD"
    else -> null
}

@Composable
fun QualityBadge(label: String?, modifier: Modifier = Modifier) {
    if (label == null) return
    val c = when (label) {
        "SD" -> Dim
        "HD" -> AccentLight
        "FHD" -> Color(0xFF7BD88F)
        else -> Color(0xFFFF9F43)
    }
    Text(
        label,
        color = c,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, c, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Red tag for channels that failed to play last time. */
@Composable
fun DeadBadge(modifier: Modifier = Modifier) {
    val c = Color(0xFFFF6B6B)
    Text(
        "OFFLINE",
        color = c,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, c, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
