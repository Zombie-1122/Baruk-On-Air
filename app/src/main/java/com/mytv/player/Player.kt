@file:OptIn(UnstableApi::class)

package com.mytv.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.app.PictureInPictureParams
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Rational
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import android.util.Base64
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.Locale

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** State shared between the on-screen controls and whichever engine is playing. */
private class PlayState {
    var buffering by mutableStateOf(true)
    var ready by mutableStateOf(false)      // has really started playing (used to clear the OFFLINE tag)
    var error by mutableStateOf<String?>(null)
    var playWhenReady by mutableStateOf(true)
    var attempt by mutableIntStateOf(0)
    var resizeIdx by mutableIntStateOf(0)
    var pos by mutableLongStateOf(0L)
    var dur by mutableLongStateOf(0L)
    var forceHls = false
    var uaIdx = 0 // index into UA_LIST, used when the playlist gives no user-agent
    var toggle: (() -> Unit)? = null
    var seek: ((Long) -> Unit)? = null

    // tracks / quality
    var tracks by mutableStateOf(Tracks.EMPTY)
    var vw by mutableIntStateOf(0)          // size of the picture being shown
    var vh by mutableIntStateOf(0)
    var maxHeight by mutableIntStateOf(0)   // tallest video track seen for the current channel
    var codecAlt by mutableStateOf(false)   // "Switch codec": prefer the other decoder
    var loadedUrl: String? = null           // url that the player is really playing right now
    var select: ((Int, Tracks.Group?, Int) -> Unit)? = null // group == null -> automatic
    var disable: ((Int) -> Unit)? = null
}

private val UA_LIST = listOf(
    DEFAULT_UA,
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
    "Lavf/60.16.100",
    "okhttp/4.12.0",
)

private val EXO_RESIZE = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT,
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    AspectRatioFrameLayout.RESIZE_MODE_FILL,
)

private fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** Remembered while the app is open so the chosen brightness sticks between channels. -1 = system default. */
private var savedBright = -1f

private val PSel = Color(0xCC2B2300)
private val PDim = Color(0xFF8C99AB)
private val PAccent = Color(0xFFFFD54F)
private val PBtnBg = Color(0xCC0B0B0B)
private val PPanel = Color(0xF0111111)
private val PBtnLine = Color(0x33FFFFFF)

private fun systemBrightness(ctx: Context): Float = runCatching {
    android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
}.getOrDefault(0.5f)

@Composable
fun PlayerScreen(
    channels: List<Channel>,
    startIndex: Int,
    favorites: Set<String>,
    epg: EpgIndex,
    onFav: (Channel) -> Unit,
    onClose: () -> Unit,
    onExternal: (Channel) -> Unit,
    onWatched: (Channel) -> Unit,
    onTune: (Channel) -> Unit,
    onDead: (Channel) -> Unit,
    onAlive: (Channel) -> Unit,
    onHeight: (Channel, Int) -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val activity = remember { ctx.findActivity() }

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, channels.lastIndex)) }
    var controls by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    // 0 none, 1 quality, 2 audio, 3 subtitles, 4 guide, 5 brightness
    var panel by remember { mutableIntStateOf(0) }
    var keyNav by remember { mutableStateOf(false) } // controls were opened with a remote -> focus the play button
    var bright by remember { mutableFloatStateOf(savedBright) }
    val st = remember { PlayState() }
    val focus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val ch = channels[index]
    val isVod = st.dur > 0L // known duration = recorded video; live streams report 0

    fun go(i: Int) {
        st.uaIdx = 0
        st.forceHls = false
        st.error = null
        st.ready = false
        st.pos = 0L
        st.dur = 0L
        st.maxHeight = 0
        st.vw = 0
        st.vh = 0
        index = (i + channels.size) % channels.size
        tick++
    }

    fun skip(deltaMs: Long) {
        val target = (st.pos + deltaMs).coerceAtLeast(0L)
        st.seek?.invoke(if (st.dur > 0) target.coerceAtMost(st.dur) else target)
        tick++
    }

    // remember the real resolution so the channel list can show SD / HD / FHD
    LaunchedEffect(st.maxHeight, ch.url) {
        if (st.maxHeight > 0) onHeight(ch, st.maxHeight)
    }
    val quality = qualityLabel(st.maxHeight) ?: nameQuality(ch.name)
    BackHandler(enabled = panel != 0) { panel = 0 }

    // mark channels that fail (only when the phone is online, so being offline doesn't tag everything) / clear when they play
    LaunchedEffect(st.error, ch.url) {
        if (st.error != null && isOnline(ctx)) onDead(ch)
    }
    LaunchedEffect(st.ready, ch.url) {
        if (st.ready) onAlive(ch)
    }

    // picture-in-picture (live TV only)
    val pipOk = remember { Build.VERSION.SDK_INT >= 26 && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    val canPip = pipOk && ch.kind == Kind.LIVE
    val inPip = Pip.active
    val closeNow by rememberUpdatedState(onClose)
    DisposableEffect(canPip) {
        Pip.playing = canPip
        onDispose { Pip.playing = false }
    }
    LaunchedEffect(st.vw, st.vh) {
        if (st.vw > 0 && st.vh > 0) {
            val r = st.vw.toFloat() / st.vh
            if (r in 0.45f..2.3f) Pip.setRatio(st.vw, st.vh) else Pip.setRatio(16, 9)
        }
    }
    LaunchedEffect(inPip) {
        if (inPip) { controls = false; panel = 0 }
    }
    // closing the little PiP window stops the activity: stop playing too
    DisposableEffect(activity) {
        val owner = activity as? LifecycleOwner
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && Pip.active) closeNow()
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }

    // count it as "watched" (for Frequency sort) only after a few seconds, so channel surfing doesn't count
    LaunchedEffect(ch.url) {
        onTune(ch) // so the list is positioned on this channel when you come back
        delay(8000)
        onWatched(ch)
    }

    // auto-hide controls
    LaunchedEffect(controls, tick) {
        if (controls) {
            delay(4000)
            controls = false
        }
    }

    // focus: hidden controls -> the screen itself (remote up/down = channel); remote-opened controls -> play button
    LaunchedEffect(controls, keyNav) {
        if (!controls) {
            runCatching { focus.requestFocus() }
        } else if (keyNav) {
            delay(120)
            runCatching { playFocus.requestFocus() }
        }
    }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

    // screen brightness (window level, restored when you leave the player)
    val window = activity?.window
    val originalBright = remember { window?.attributes?.screenBrightness ?: -1f }
    LaunchedEffect(bright) {
        window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = bright
            w.attributes = lp
        }
        savedBright = bright
    }
    DisposableEffect(Unit) {
        onDispose {
            window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = originalBright
                w.attributes = lp
            }
        }
    }

    // fullscreen + keep screen on; cleanup
    DisposableEffect(Unit) {
        val w = activity?.window
        val ic = w?.let { WindowCompat.getInsetsController(it, view) }
        ic?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ic?.hide(WindowInsetsCompat.Type.systemBars())
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            ic?.show(WindowInsetsCompat.Type.systemBars())
            w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (panel != 0) return@onPreviewKeyEvent false
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (controls) {
                    tick++
                    return@onPreviewKeyEvent false
                }
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp -> {
                        go(index - 1)
                        true
                    }
                    Key.DirectionDown, Key.ChannelDown -> {
                        go(index + 1)
                        true
                    }
                    Key.DirectionLeft -> {
                        if (isVod) skip(-10_000L) else { keyNav = true; controls = true; tick++ }
                        true
                    }
                    Key.DirectionRight -> {
                        if (isVod) skip(10_000L) else { keyNav = true; controls = true; tick++ }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        keyNav = true
                        controls = true
                        tick++
                        true
                    }
                    else -> false
                }
            }
    ) {
        ExoSurface(ch, st)

        // radio: show the logo and name instead of an empty black screen
        if (ch.kind == Kind.RADIO && !controls) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Logo(ch.logo, ch.name, 84.dp, width = 140.dp)
                Text(ch.name, color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }

        // tap layer (not focusable, so it never steals the remote's focus)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        keyNav = false
                        if (panel != 0) {
                            panel = 0
                        } else {
                            controls = !controls
                            tick++
                        }
                    }
                }
        )

        if (st.buffering && st.error == null && !controls) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        st.error?.let { err ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Can't play this one", color = Color.White)
                Text(
                    err,
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { st.forceHls = false; st.uaIdx = 0; st.error = null; st.attempt++ }) { Text("Retry") }
                    Button(onClick = { onExternal(ch) }) { Text("Open in app") }
                    TextButton(onClick = { go(index + 1) }) { Text("Next") }
                }
            }
        }

        AnimatedVisibility(visible = controls && !inPip, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // ---- top bar: back, channel logo + name, clock, speed, resolution, favourite ----
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RoundBtn(44.dp, onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Logo(ch.logo, ch.name, 40.dp, width = 56.dp)
                    val nowProg = if (ch.kind == Kind.LIVE) epg.of(ch).nowAt(System.currentTimeMillis()) else null
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ch.name,
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (quality != null) {
                                Spacer(Modifier.width(8.dp))
                                QualityBadge(quality)
                            }
                        }
                        Text(
                            if (nowProg != null) "${hm(nowProg.start)} - ${hm(nowProg.stop)}  ${nowProg.title}" else ch.group,
                            color = Color(0xCCFFFFFF),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ClockText(color = Color.White, dim = Color(0xCCFFFFFF))
                    NetSpeed(color = Color.White)
                    if (st.vw > 0 && st.vh > 0) InfoPill("${st.vw}x${st.vh}", color = Color.White)
                    val fav = ch.url in favorites
                    RoundBtn(44.dp, { onFav(ch); tick++ }) {
                        Icon(
                            if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "Favorite",
                            tint = if (fav) Heart else Color.White,
                        )
                    }
                }

                // ---- centre: previous / play-pause / next ----
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundBtn(52.dp, { go(index - 1) }) { Text("⏮", fontSize = 22.sp, color = Color.White) }
                    if (isVod) RoundBtn(44.dp, { skip(-10_000L) }) { Text("-10", fontSize = 13.sp, color = Color.White) }
                    RoundBtn(
                        76.dp,
                        { st.toggle?.invoke(); tick++ },
                        modifier = Modifier.focusRequester(playFocus),
                        fill = Accent,
                    ) {
                        if (st.buffering && st.error == null) {
                            CircularProgressIndicator(Modifier.size(32.dp), color = OnAccent, strokeWidth = 3.dp)
                        } else {
                            PlayPause(st.playWhenReady)
                        }
                    }
                    if (isVod) RoundBtn(44.dp, { skip(10_000L) }) { Text("+10", fontSize = 13.sp, color = Color.White) }
                    RoundBtn(52.dp, { go(index + 1) }) { Text("⏭", fontSize = 22.sp, color = Color.White) }
                }

                // ---- bottom: LIVE pill + one tidy row of actions ----
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (isVod && st.dur > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmt(st.pos), color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = st.pos.toFloat().coerceIn(0f, st.dur.toFloat()),
                                onValueChange = {
                                    st.seek?.invoke(it.toLong())
                                    tick++
                                },
                                valueRange = 0f..st.dur.toFloat(),
                                colors = SliderDefaults.colors(thumbColor = PAccent, activeTrackColor = Accent),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            Text(fmt(st.dur), color = Color.White, fontSize = 12.sp)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (!isVod) LivePill()
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PBtn("Audio", { panel = 2; controls = false })
                                if (ch.kind == Kind.LIVE) {
                                    PBtn("Subtitles", { panel = 3; controls = false })
                                    PBtn("Quality" + (quality?.let { " · $it" } ?: ""), { panel = 1; controls = false })
                                    PBtn(listOf("Fit", "Zoom", "Fill")[st.resizeIdx], {
                                        st.resizeIdx = (st.resizeIdx + 1) % 3
                                        tick++
                                    })
                                    IconPBtn(0) { panel = 4; controls = false }
                                }
                                IconPBtn(1) { panel = 5; controls = false }
                                if (canPip) IconPBtn(2) { Pip.enter(activity) }
                                if (ch.kind == Kind.RADIO) PBtn("Open in app", { onExternal(ch) })
                            }
                        }
                    }
                }
            }
        }

        when (panel) {
            1, 2, 3 -> TracksPanel(
                startPage = panel,
                name = ch.name,
                quality = quality,
                st = st,
                onClose = { panel = 0 },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            4 -> GuidePanel(ch, epg, { panel = 0 }, Modifier.align(Alignment.CenterEnd))
            5 -> {
                val sys = remember { systemBrightness(ctx) }
                BrightPanel(
                    level = if (bright < 0f) sys else bright,
                    auto = bright < 0f,
                    onChange = { bright = it },
                    onClose = { panel = 0 },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@Composable
private fun RoundBtn(
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = PBtnBg,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, PBtnLine, CircleShape)
            .tvFocus(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PBtn(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .height(40.dp)
            .clip(shape)
            .background(PBtnBg)
            .border(1.dp, PBtnLine, shape)
            .tvFocus(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Square icon-only button. kind: 0 guide, 1 brightness, 2 picture-in-picture. */
@Composable
private fun IconPBtn(kind: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(40.dp)
            .clip(shape)
            .background(PBtnBg)
            .border(1.dp, PBtnLine, shape)
            .tvFocus(shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(kind) }
}

@Composable
private fun Glyph(kind: Int, color: Color = Color.White) {
    Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension
        val w = s * 0.09f
        when (kind) {
            0 -> for (i in 0..2) { // programme list
                val y = s * (0.24f + 0.26f * i)
                drawCircle(color, w * 0.9f, Offset(s * 0.14f, y))
                drawLine(color, Offset(s * 0.32f, y), Offset(s * 0.92f, y), strokeWidth = w, cap = StrokeCap.Round)
            }
            1 -> { // brightness: sun
                val c = Offset(s / 2, s / 2)
                drawCircle(color, s * 0.17f, c)
                for (k in 0 until 8) {
                    val a = k * PI / 4
                    val dx = cos(a).toFloat()
                    val dy = sin(a).toFloat()
                    drawLine(
                        color,
                        Offset(c.x + dx * s * 0.30f, c.y + dy * s * 0.30f),
                        Offset(c.x + dx * s * 0.46f, c.y + dy * s * 0.46f),
                        strokeWidth = w,
                        cap = StrokeCap.Round,
                    )
                }
            }
            else -> { // picture-in-picture: screen with a small window
                drawRoundRect(
                    color,
                    topLeft = Offset(s * 0.07f, s * 0.18f),
                    size = Size(s * 0.86f, s * 0.64f),
                    cornerRadius = CornerRadius(s * 0.08f),
                    style = Stroke(w),
                )
                drawRoundRect(
                    color,
                    topLeft = Offset(s * 0.48f, s * 0.46f),
                    size = Size(s * 0.33f, s * 0.24f),
                    cornerRadius = CornerRadius(s * 0.04f),
                )
            }
        }
    }
}

/** Picture-in-picture state shared with MainActivity. */
object Pip {
    var active by mutableStateOf(false) // the activity is currently shown as the small floating window
    var playing = false                 // live video is on screen, so leaving the app should float it
    private var rw = 16
    private var rh = 9

    fun setRatio(w: Int, h: Int) {
        rw = w
        rh = h
    }

    fun enter(activity: Activity?) {
        if (activity == null || Build.VERSION.SDK_INT < 26) return
        runCatching {
            activity.enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(rw, rh)).build()
            )
        }
    }
}

private fun isOnline(ctx: Context): Boolean = runCatching {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}.getOrDefault(true)

@Composable
private fun LivePill() {
    Row(
        Modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE0245E)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(8.dp))
        Text("LIVE", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun PlayPause(playing: Boolean) {
    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        if (playing) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(2) { Box(Modifier.width(6.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(OnAccent)) }
            }
        } else {
            Icon(Icons.Default.PlayArrow, "Play", tint = OnAccent, modifier = Modifier.size(34.dp))
        }
    }
}

// ---------------------------------------------------------------- side panels (guide, brightness)

@Composable
private fun SidePanel(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier,
    width: Float = 0.48f,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxHeight()
            .fillMaxWidth(width)
            .background(PPanel)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClose, tint = Color.White)
            Text(
                title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.width(44.dp))
        }
        content()
    }
}

/** Current programme in full, then what's on next. */
@Composable
private fun GuidePanel(ch: Channel, epg: EpgIndex, onClose: () -> Unit, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = System.currentTimeMillis()
        }
    }
    val progs = epg.of(ch)
    val cur = progs.nowAt(now)
    val next = progs.upcoming(now, 24)
    SidePanel("TV Guide", onClose, modifier) {
        Row(
            Modifier.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Logo(ch.logo, ch.name, 34.dp, width = 48.dp)
            Text(ch.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (cur == null && next.isEmpty()) {
                Text("No programme info for this channel", color = PDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
            if (cur != null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PSel).padding(14.dp)) {
                    Text("NOW", color = PAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    Text(cur.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    Text("${hm(cur.start)} - ${hm(cur.stop)}", color = PDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    Bar((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1), Modifier.padding(vertical = 8.dp))
                    if (cur.desc.isNotBlank()) {
                        Text(cur.desc, color = Color(0xCCFFFFFF), fontSize = 13.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            if (next.isNotEmpty()) {
                Text("NEXT", color = PDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                for (p in next) {
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text(hm(p.start), color = PAccent, fontSize = 13.sp, modifier = Modifier.width(56.dp))
                        Text(p.title, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrightPanel(level: Float, auto: Boolean, onChange: (Float) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    SidePanel("Brightness", onClose, modifier, width = 0.42f) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            Text(
                if (auto) "Auto (system)" else "${(level * 100).toInt()}%",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Slider(
                value = level.coerceIn(0.03f, 1f),
                onValueChange = onChange,
                valueRange = 0.03f..1f,
                colors = SliderDefaults.colors(thumbColor = PAccent, activeTrackColor = Accent),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                PBtn("Auto", { onChange(-1f) })
                PBtn("Night", { onChange(0.06f) })
                PBtn("Full", { onChange(1f) })
            }
        }
    }
}

// ---------------------------------------------------------------- "Media tracks" panel

private class TrackRow(val group: Tracks.Group, val index: Int) {
    val format: Format get() = group.getTrackFormat(index)
    val selected: Boolean get() = group.isTrackSelected(index)
}

private fun rowsOf(groups: List<Tracks.Group>): List<TrackRow> =
    groups.flatMap { g -> (0 until g.length).map { TrackRow(g, it) } }

private fun mbps(bitrate: Int): String =
    if (bitrate > 0) ", %.2f Mbps".format(Locale.US, bitrate / 1_000_000f) else ""

private fun langName(f: Format): String? {
    val l = f.language?.takeIf { it.isNotBlank() && it != "und" } ?: return null
    return Locale.forLanguageTag(l).displayLanguage.takeIf { it.isNotBlank() } ?: l
}

private fun videoLabel(f: Format): String {
    val q = qualityLabel(f.height)
    val size = if (f.width > 0 && f.height > 0) "${f.width} \u00D7 ${f.height}" else "Video"
    return size + (if (q != null) "  $q" else "") + mbps(f.bitrate)
}

private fun audioLabel(f: Format, n: Int): String {
    val ch = when (f.channelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }
    return (f.label ?: langName(f) ?: "Track $n") + (if (ch != null) ", $ch" else "") + mbps(f.bitrate)
}

private fun textLabel(f: Format, n: Int): String = f.label ?: langName(f) ?: "Subtitle $n"

@Composable
private fun TracksPanel(startPage: Int, name: String, quality: String?, st: PlayState, onClose: () -> Unit, modifier: Modifier) {
    var page by remember(startPage) { mutableIntStateOf(startPage) } // 0 overview, 1 quality, 2 audio, 3 subtitles
    val first = remember { FocusRequester() }
    LaunchedEffect(page) { runCatching { first.requestFocus() } }

    val groups = st.tracks.groups
    val video = rowsOf(groups.filter { it.type == C.TRACK_TYPE_VIDEO }).sortedBy { it.format.height }
    val audio = rowsOf(groups.filter { it.type == C.TRACK_TYPE_AUDIO })
    val text = rowsOf(groups.filter { it.type == C.TRACK_TYPE_TEXT })
    val titles = listOf("Media tracks", "Quality", "Audio", "Subtitles")

    Column(
        modifier
            .fillMaxHeight()
            .fillMaxWidth(0.48f)
            .background(PPanel)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Back", { if (page == 0 || startPage != 0) onClose() else page = 0 }, tint = Color.White)
            Text(
                titles[page],
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.width(44.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, color = Color(0xE6FFFFFF), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (quality != null) {
                Spacer(Modifier.width(8.dp))
                QualityBadge(quality)
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (page) {
                0 -> {
                    PanelRow(
                        title = "Video (${video.size})",
                        lines = video.map { videoLabel(it.format) }.ifEmpty { listOf("No video info yet") },
                        requester = first,
                        onClick = { page = 1 },
                    )
                    PanelRow(
                        title = "Audio (${audio.size})",
                        lines = audio.mapIndexed { i, r -> audioLabel(r.format, i + 1) }.ifEmpty { listOf("No audio info yet") },
                        onClick = { page = 2 },
                    )
                    PanelRow(
                        title = "Subtitles (${text.size})",
                        lines = text.mapIndexed { i, r -> textLabel(r.format, i + 1) }.ifEmpty { listOf("None") },
                        onClick = { page = 3 },
                    )
                    PanelRow(
                        title = "Switch codec (Load stream via different decoder)",
                        lines = listOf(if (st.codecAlt) "Decoder: alternative" else "Decoder: default"),
                        onClick = { st.codecAlt = !st.codecAlt },
                    )
                }
                1 -> {
                    val videoAuto = video.count { it.selected } != 1 || video.size == 1
                    ChoiceRow("Auto (adaptive)", videoAuto, first) { st.select?.invoke(C.TRACK_TYPE_VIDEO, null, 0) }
                    for (r in video.asReversed()) {
                        ChoiceRow(videoLabel(r.format), !videoAuto && r.selected) {
                            st.select?.invoke(C.TRACK_TYPE_VIDEO, r.group, r.index)
                        }
                    }
                    PanelRow(
                        title = "Switch codec",
                        lines = listOf(if (st.codecAlt) "Decoder: alternative (tap to go back)" else "Decoder: default - tap if the picture is broken"),
                        onClick = { st.codecAlt = !st.codecAlt },
                    )
                }
                2 -> {
                    if (audio.isEmpty()) Text("No audio tracks", color = PDim, modifier = Modifier.padding(16.dp))
                    audio.forEachIndexed { i, r ->
                        ChoiceRow(audioLabel(r.format, i + 1), r.selected, if (i == 0) first else null) {
                            st.select?.invoke(C.TRACK_TYPE_AUDIO, r.group, r.index)
                        }
                    }
                }
                else -> {
                    ChoiceRow("Off", text.none { it.selected }, first) { st.disable?.invoke(C.TRACK_TYPE_TEXT) }
                    text.forEachIndexed { i, r ->
                        ChoiceRow(textLabel(r.format, i + 1), r.selected) {
                            st.select?.invoke(C.TRACK_TYPE_TEXT, r.group, r.index)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelRow(title: String, lines: List<String>, onClick: () -> Unit, requester: FocusRequester? = null) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .tvFocus(shape)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        for (l in lines.take(7)) {
            Text(l, color = Color(0xCCFFFFFF), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, requester: FocusRequester? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(if (selected) PSel else Color.Transparent)
            .tvFocus(shape)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "\u25CF" else "\u25CB", color = if (selected) PAccent else PDim, fontSize = 14.sp)
        Spacer(Modifier.width(12.dp))
        Text(label, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- Built-in (Media3 / ExoPlayer)

private val HEX32 = Regex("^[0-9a-fA-F]{32}$")

/** "kid" or "key" as 32 hex chars -> base64url (what a ClearKey JWK wants). Anything else is passed through. */
private fun jwkValue(v: String): String {
    if (!HEX32.matches(v)) return v
    val bytes = ByteArray(16) { v.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

private val KV_PAIR = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")

private fun jwk(pairs: List<Pair<String, String>>): ByteArray? {
    if (pairs.isEmpty()) return null
    val sb = StringBuilder("{\"keys\":[")
    pairs.forEachIndexed { i, (kid, k) ->
        if (i > 0) sb.append(',')
        sb.append("{\"kty\":\"oct\",\"k\":\"").append(k).append("\",\"kid\":\"").append(kid).append("\"}")
    }
    sb.append("],\"type\":\"temporary\"}")
    return sb.toString().toByteArray()
}

/**
 * #KODIPROP license_key for ClearKey comes in three shapes:
 *  1. ready JWK:            {"keys":[{"kty":"oct","k":"..","kid":".."}],"type":"temporary"}
 *  2. map of hex pairs:     {"<kid hex>": "<key hex>", ...}
 *  3. plain list:           <kid hex>:<key hex>,<kid hex>:<key hex>
 */
private fun clearKeyJson(raw: String): ByteArray? {
    val t = raw.trim()
    if (t.startsWith("{")) {
        if (t.contains("\"keys\"")) return t.toByteArray()
        return jwk(KV_PAIR.findAll(t).map { jwkValue(it.groupValues[1].trim()) to jwkValue(it.groupValues[2].trim()) }.toList())
    }
    return jwk(t.split(',').mapNotNull { p ->
        val kv = p.trim().split(':')
        if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) jwkValue(kv[0].trim()) to jwkValue(kv[1].trim()) else null
    })
}

/** Builds the right source for a channel: plain/HLS/DASH, with ClearKey or Widevine when the playlist asks for it. */
private fun buildSource(ch: Channel, dsf: DataSource.Factory, forceHls: Boolean): MediaSource {
    val b = MediaItem.Builder().setUri(ch.url)
    when (ch.manifest) {
        "dash", "mpd" -> b.setMimeType(MimeTypes.APPLICATION_MPD)
        "hls" -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
    }
    var drm: DrmSessionManager? = null
    val key = ch.drmKey
    val type = ch.drmType ?: ""
    if (key != null && type.contains("clearkey") && key.trim().startsWith("http", ignoreCase = true)) {
        b.setDrmConfiguration(
            MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                .setLicenseUri(key.trim())
                .setLicenseRequestHeaders(mapOf("User-Agent" to (ch.userAgent ?: DEFAULT_UA)))
                .build()
        )
    } else if (key != null && type.contains("clearkey")) {
        val json = clearKeyJson(key)
        if (json != null) {
            b.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build())
            drm = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(LocalMediaDrmCallback(json))
        }
    } else if (key != null && type.contains("widevine")) {
        b.setDrmConfiguration(
            MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                .setLicenseUri(key.substringBefore('|').trim())
                .setLicenseRequestHeaders(mapOf("User-Agent" to (ch.userAgent ?: DEFAULT_UA)))
                .build()
        )
    }
    val item = b.build()
    if (forceHls) return HlsMediaSource.Factory(dsf).createMediaSource(item)
    val factory = DefaultMediaSourceFactory(dsf)
    val manager = drm
    if (manager != null) factory.setDrmSessionManagerProvider { manager }
    return factory.createMediaSource(item)
}

/** "Switch codec": when [alt] is set, hardware/software decoder order is reversed so another decoder is tried first. */
private fun buildExo(ctx: Context, alt: Boolean): ExoPlayer {
    val rf = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
    if (alt) {
        rf.setMediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).reversed()
        }
    }
    return ExoPlayer.Builder(ctx, rf).build()
}

@Composable
private fun ExoSurface(ch: Channel, st: PlayState) {
    val ctx = LocalContext.current
    val chNow by rememberUpdatedState(ch)
    val exo = remember(st.codecAlt) { buildExo(ctx, st.codecAlt) }

    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                st.buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY && st.loadedUrl == chNow.url) st.ready = true
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                st.playWhenReady = playWhenReady
            }

            override fun onTracksChanged(tracks: Tracks) {
                st.tracks = tracks
                if (st.loadedUrl != chNow.url) return
                var h = 0
                for (g in tracks.groups) {
                    if (g.type != C.TRACK_TYPE_VIDEO) continue
                    for (i in 0 until g.length) h = maxOf(h, g.getTrackFormat(i).height)
                }
                if (h > st.maxHeight) st.maxHeight = h
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (st.loadedUrl != chNow.url) return
                st.vw = videoSize.width
                st.vh = videoSize.height
                if (videoSize.height > st.maxHeight) st.maxHeight = videoSize.height
            }

            override fun onPlayerError(error: PlaybackException) {
                val chain = generateSequence<Throwable>(error) { it.cause }.toList()
                val unknownFormat = chain.any { it is UnrecognizedInputFormatException }
                val http = chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                if (unknownFormat && !st.forceHls) {
                    // stream without .m3u8 extension -> try as HLS
                    st.forceHls = true
                    st.attempt++
                } else if (http != null && http.responseCode in 400..499 && chNow.userAgent == null &&
                    st.uaIdx < UA_LIST.lastIndex
                ) {
                    // some servers refuse the default player name: retry as a different client
                    st.uaIdx++
                    st.attempt++
                } else {
                    st.buffering = false
                    val detail = when {
                        http != null -> "HTTP ${http.responseCode}"
                        else -> chain.drop(1).firstNotNullOfOrNull { it.message?.takeIf { m -> m.isNotBlank() } }?.take(90)
                    }
                    st.error = error.errorCodeName + (if (detail != null) "\n$detail" else "")
                }
            }
        }
        exo.addListener(l)
        st.toggle = { exo.playWhenReady = !exo.playWhenReady }
        st.seek = { ms -> exo.seekTo(ms) }
        st.select = { type, group, idx ->
            val b = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
            if (group == null) {
                b.clearOverridesOfType(type)
            } else {
                b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, idx))
            }
            exo.trackSelectionParameters = b.build()
        }
        st.disable = { type ->
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build()
        }
        onDispose {
            exo.removeListener(l)
            st.toggle = null
            st.seek = null
            st.select = null
            st.disable = null
            exo.release()
        }
    }

    LaunchedEffect(exo) {
        while (true) {
            val live = exo.isCurrentMediaItemLive
            st.pos = if (live) 0L else exo.currentPosition
            st.dur = if (live) 0L else exo.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    LaunchedEffect(exo, ch.url, st.attempt) {
        st.error = null
        st.ready = false
        st.buffering = true
        st.maxHeight = 0
        st.vw = 0
        st.vh = 0
        st.tracks = Tracks.EMPTY
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ch.userAgent ?: UA_LIST[st.uaIdx.coerceIn(0, UA_LIST.lastIndex)])
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        ch.referrer?.let { ref ->
            val headers = mutableMapOf("Referer" to ref)
            // many CDNs also check Origin (scheme://host of the referrer)
            runCatching { java.net.URI(ref) }.getOrNull()?.let { u ->
                if (u.scheme != null && u.authority != null) headers["Origin"] = "${u.scheme}://${u.authority}"
            }
            http.setDefaultRequestProperties(headers)
        }
        val dsf = DefaultDataSource.Factory(ctx, http)
        exo.setMediaSource(buildSource(ch, dsf, st.forceHls))
        st.loadedUrl = ch.url
        exo.prepare()
        exo.playWhenReady = true
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            PlayerView(c).apply {
                useController = false
                this.player = exo
            }
        },
        update = {
            it.player = exo
            it.resizeMode = EXO_RESIZE[st.resizeIdx]
        },
    )
}
