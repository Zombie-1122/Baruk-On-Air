package com.mytv.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---- palette: dark (default) or light, switched in Settings. Getters read Compose state, so screens recolor live. ----
object Pal {
    var dark by mutableStateOf(true)
}

val Bg get() = if (Pal.dark) Color(0xFF000000) else Color(0xFFFAF7EE)
val Panel get() = if (Pal.dark) Color(0xCC121212) else Color(0xE6FFFFFF)
val Accent = Color(0xFFFFC400)       // yellow
val OnAccent = Color(0xFF111111)     // text/icons on yellow
val AccentLight get() = if (Pal.dark) Color(0xFFFFD54F) else Color(0xFF8A6700)  // text/marks
val Sel get() = if (Pal.dark) Color(0xCC2B2300) else Color(0xFFFFF0B8)
val SelBorder = Color(0xFFC9A000)
val OnSel get() = if (Pal.dark) Color.White else Color(0xFF3A2E00)
val Txt get() = if (Pal.dark) Color(0xFFF2F2F2) else Color(0xFF1A1A1A)
val Dim get() = if (Pal.dark) Color(0xFF9A9A9A) else Color(0xFF666666)
val Heart = Color(0xFFFF5A5F)
val Hair get() = if (Pal.dark) Color(0x22FFFFFF) else Color(0x22000000)
/** faint tint for fields/tracks that works on both themes */
val Veil get() = if (Pal.dark) Color(0x33FFFFFF) else Color(0x14000000)

/** Night-village photo, darkened so text stays readable. Drawn once, behind every non-player screen. */
@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Bg)) {
        Image(
            painter = painterResource(R.drawable.bg_night),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(
                        if (Pal.dark) listOf(Color(0xB3000000), Color(0xEB000000))
                        else listOf(Color(0xE6FAF7EE), Color(0xF2FAF7EE))
                    ))
        )
        content()
    }
}

/** White ring while focused (D-pad / remote). Put it BEFORE clickable in the chain. */
@Composable
fun Modifier.tvFocus(shape: Shape, onFocus: ((Boolean) -> Unit)? = null): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged {
            focused = it.isFocused || it.hasFocus
            onFocus?.invoke(focused)
        }
        .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(36.dp)
            .clip(shape)
            .background(if (selected) Accent else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(1.dp, Txt.copy(alpha = 0.35f), shape))
            .tvFocus(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = if (selected) OnAccent else Txt,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun IconBtn(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    tint: Color = Txt,
    badge: Boolean = false,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .tvFocus(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(24.dp))
        if (badge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(Accent)
            )
        }
    }
}

/** Channel logo: drawn as-is, scaled to fit its box (no tile or border). Only the no-logo placeholder gets a faint tile. */
@Composable
fun Logo(url: String?, name: String, size: Dp, fit: Boolean = true, width: Dp = size) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            alignment = Alignment.CenterStart,
            modifier = Modifier.size(width, size),
        )
    } else {
        Box(
            Modifier.size(width, size).clip(RoundedCornerShape(10.dp)).background(Veil),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), color = Dim, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Veil)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(Accent)
        )
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(16.dp),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** Back arrow + title + optional progress line + snackbar; content fills the rest. */
@Composable
fun ScreenFrame(
    title: String,
    onBack: () -> Unit,
    busy: Boolean = false,
    snack: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Text(
                    title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                ClockText()
            }
            if (busy) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp),
                    color = Accent,
                    trackColor = Color.Transparent,
                )
            }
            content()
        }
        if (snack != null) SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }
}

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun hm(ms: Long): String = HM.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

/** Time on top, date underneath, like the reference player. Ticks by itself. */
@Composable
fun ClockText(
    modifier: Modifier = Modifier,
    color: Color = Txt,
    dim: Color = Dim,
    align: Alignment.Horizontal = Alignment.End,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(10_000)
        }
    }
    val t = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    Column(modifier, horizontalAlignment = align) {
        Text(CLOCK.format(t), color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(DATE.format(t), color = dim, fontSize = 11.sp, maxLines = 1)
    }
}

private fun rxBytes(uid: Int): Long {
    val u = TrafficStats.getUidRxBytes(uid)
    return if (u >= 0) u else TrafficStats.getTotalRxBytes()
}

/** Small outlined label (used for network speed and resolution). */
@Composable
fun InfoPill(text: String, modifier: Modifier = Modifier, color: Color = Txt) {
    Box(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, maxLines = 1)
    }
}

/** Live download speed of this app (updates every second). */
@Composable
fun NetSpeed(modifier: Modifier = Modifier, color: Color = Txt) {
    var bps by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val uid = Process.myUid()
        var last = rxBytes(uid)
        var lastT = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            val cur = rxBytes(uid)
            val t = SystemClock.elapsedRealtime()
            bps = ((cur - last) * 8000L / (t - lastT).coerceAtLeast(1L)).coerceAtLeast(0L)
            last = cur
            lastT = t
        }
    }
    val label = if (bps >= 1_000_000L) String.format(Locale.US, "%.1f Mbps", bps / 1_000_000f)
    else String.format(Locale.US, "%d Kbps", bps / 1000L)
    InfoPill("\u2193 $label", modifier, color)
}
