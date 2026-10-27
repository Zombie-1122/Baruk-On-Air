@file:OptIn(UnstableApi::class)

package com.mytv.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Base64
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
    onHeight: (Channel, Int) -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val activity = remember { ctx.findActivity() }

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, channels.lastIndex)) }
    var controls by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf(false) }
    val st = remember { PlayState() }
    val focus = remember { FocusRequester() }
    val ch = channels[index]
    val isVod = st.dur > 0L // known duration = recorded video; live streams report 0

    fun go(i: Int) {
        st.uaIdx = 0
        st.forceHls = false
        st.error = null
        st.pos = 0L
        st.dur = 0L
        st.maxHeight = 0
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
    BackHandler(enabled = panel) { panel = false }

    // count it as "watched" (for Frequency sort) only after a few seconds, so channel surfing doesn't count
    LaunchedEffect(ch.url) {
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

    // when controls hide, take keyboard/remote focus back
    LaunchedEffect(controls) {
        if (!controls) runCatching { focus.requestFocus() }
    }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

    // fullscreen + keep screen on; cleanup
    DisposableEffect(Unit) {
        val window = activity?.window
        val ic = window?.let { WindowCompat.getInsetsController(it, view) }
        ic?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ic?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            ic?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (panel) return@onPreviewKeyEvent false
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
                        if (isVod) skip(-10_000L) else { controls = true; tick++ }
                        true
                    }
                    Key.DirectionRight -> {
                        if (isVod) skip(10_000L) else { controls = true; tick++ }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        controls = true
                        tick++
                        true
                    }
                    else -> false
                }
            }
    ) {
        ExoSurface(ch, st)

        // radio: show a big label instead of an empty black screen
        if (ch.kind == Kind.RADIO) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📻", fontSize = 64.sp)
                Text(ch.name, color = Color.White, fontSize = 20.sp)
            }
        }

        // tap layer (not focusable, so it never steals the remote's focus)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        if (panel) {
                            panel = false
                        } else {
                            controls = !controls
                            tick++
                        }
                    }
                }
        )

        if (st.buffering && st.error == null) {
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

        AnimatedVisibility(visible = controls, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                // top bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    val nowProg = if (ch.kind == Kind.LIVE) epg.of(ch).nowAt(System.currentTimeMillis()) else null
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ch.name,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (quality != null) {
                                Spacer(Modifier.width(8.dp))
                                QualityBadge(quality)
                            }
                        }
                        if (nowProg != null) {
                            Text(
                                "${hm(nowProg.start)} - ${hm(nowProg.stop)}  ${nowProg.title}",
                                color = Color(0xCCFFFFFF),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val fav = ch.url in favorites
                    IconButton(onClick = { onFav(ch); tick++ }) {
                        Icon(
                            if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "Favorite",
                            tint = if (fav) Heart else Color.White,
                        )
                    }
                }

                // bottom bar
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(8.dp),
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
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            Text(fmt(st.dur), color = Color.White, fontSize = 12.sp)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { go(index - 1) }) { Text("⏮", fontSize = 26.sp, color = Color.White) }
                        if (isVod) {
                            TextButton(onClick = { skip(-10_000L) }) { Text("-10s", color = Color.White) }
                        }
                        TextButton(onClick = {
                            st.toggle?.invoke()
                            tick++
                        }) { PlayPause(st.playWhenReady) }
                        if (isVod) {
                            TextButton(onClick = { skip(10_000L) }) { Text("+10s", color = Color.White) }
                        }
                        TextButton(onClick = { go(index + 1) }) { Text("⏭", fontSize = 26.sp, color = Color.White) }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            st.resizeIdx = (st.resizeIdx + 1) % 3
                            tick++
                        }) { Text("Zoom", color = Color.White) }
                        TextButton(onClick = { panel = true; controls = false }) { Text("Tracks", color = Color.White) }
                        TextButton(onClick = { onExternal(ch) }) { Text("Open in app", color = Color.White) }
                    }
                }
            }
        }

        if (panel) {
            TracksPanel(
                name = ch.name,
                quality = quality,
                st = st,
                onClose = { panel = false },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun PlayPause(playing: Boolean) {
    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        if (playing) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(2) { Box(Modifier.width(6.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Color.White)) }
            }
        } else {
            Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(34.dp))
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
private fun TracksPanel(name: String, quality: String?, st: PlayState, onClose: () -> Unit, modifier: Modifier) {
    var page by remember { mutableIntStateOf(0) } // 0 overview, 1 video, 2 audio, 3 subtitles
    val first = remember { FocusRequester() }
    LaunchedEffect(page) { runCatching { first.requestFocus() } }

    val groups = st.tracks.groups
    val video = rowsOf(groups.filter { it.type == C.TRACK_TYPE_VIDEO }).sortedBy { it.format.height }
    val audio = rowsOf(groups.filter { it.type == C.TRACK_TYPE_AUDIO })
    val text = rowsOf(groups.filter { it.type == C.TRACK_TYPE_TEXT })
    val titles = listOf("Media tracks", "Video", "Audio", "Subtitles")

    Column(
        modifier
            .fillMaxHeight()
            .fillMaxWidth(0.48f)
            .background(Color(0xF0172131))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Back", { if (page == 0) onClose() else page = 0 })
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
                }
                2 -> {
                    if (audio.isEmpty()) Text("No audio tracks", color = Dim, modifier = Modifier.padding(16.dp))
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
            .background(if (selected) Sel else Color.Transparent)
            .tvFocus(shape)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "\u25CF" else "\u25CB", color = if (selected) AccentLight else Dim, fontSize = 14.sp)
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
                if (st.loadedUrl == chNow.url && videoSize.height > st.maxHeight) st.maxHeight = videoSize.height
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
        st.buffering = true
        st.maxHeight = 0
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
