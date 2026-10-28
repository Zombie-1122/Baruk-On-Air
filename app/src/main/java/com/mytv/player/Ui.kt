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
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---- palette (navy + blue, like the reference screenshot) ----
val Bg = Color(0xFF0F1620)
val Panel = Color(0xCC141C28)
val Accent = Color(0xFF0E8C7F)       // muted teal (was blue)
val AccentLight = Color(0xFF5FD3C4)  // text/marks on dark
val Sel = Color(0xCC15363A)
val SelBorder = Color(0xFF2E8B83)
val Txt = Color(0xFFE8EDF4)
val Dim = Color(0xFF8C99AB)
val Heart = Color(0xFFFF5A5F)
val Hair = Color(0x22FFFFFF)

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
                .background(Brush.verticalGradient(listOf(Color(0xB30B121B), Color(0xE60B121B))))
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
            .then(if (selected) Modifier else Modifier.border(1.dp, Color(0x55FFFFFF), shape))
            .tvFocus(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = if (selected) Color.White else Txt,
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
            Modifier.size(width, size).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), color = Dim, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
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
                    modifier = Modifier.padding(start = 8.dp),
                )
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
