@file:OptIn(UnstableApi::class)

package com.mytv.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

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
    var toggle: (() -> Unit)? = null
    var seek: ((Long) -> Unit)? = null
}

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
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val activity = remember { ctx.findActivity() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, channels.lastIndex)) }
    var controls by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    val st = remember { PlayState() }
    val focus = remember { FocusRequester() }
    val ch = channels[index]
    val isVod = ch.kind == Kind.MOVIE || ch.kind == Kind.SERIES

    fun go(i: Int) {
        st.forceHls = false
        st.error = null
        st.pos = 0L
        st.dur = 0L
        index = (i + channels.size) % channels.size
        tick++
    }

    fun skip(deltaMs: Long) {
        val target = (st.pos + deltaMs).coerceAtLeast(0L)
        st.seek?.invoke(if (st.dur > 0) target.coerceAtMost(st.dur) else target)
        tick++
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
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
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
                        controls = !controls
                        tick++
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
                Text(err, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { st.forceHls = false; st.error = null; st.attempt++ }) { Text("Retry") }
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
                        Text(ch.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        }) { Text(if (st.playWhenReady) "⏸" else "▶", fontSize = 28.sp, color = Color.White) }
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
                        TextButton(onClick = {
                            activity?.requestedOrientation = if (landscape) {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                            tick++
                        }) { Text("Rotate", color = Color.White) }
                        TextButton(onClick = { onExternal(ch) }) { Text("Open in app", color = Color.White) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Built-in (Media3 / ExoPlayer)

@Composable
private fun ExoSurface(ch: Channel, st: PlayState) {
    val ctx = LocalContext.current
    val exo = remember { ExoPlayer.Builder(ctx).build() }

    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                st.buffering = state == Player.STATE_BUFFERING
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                st.playWhenReady = playWhenReady
            }

            override fun onPlayerError(error: PlaybackException) {
                val unknownFormat = generateSequence<Throwable>(error) { it.cause }
                    .any { it is UnrecognizedInputFormatException }
                if (unknownFormat && !st.forceHls) {
                    // stream without .m3u8 extension -> try as HLS
                    st.forceHls = true
                    st.attempt++
                } else {
                    st.buffering = false
                    st.error = error.errorCodeName
                }
            }
        }
        exo.addListener(l)
        st.toggle = { exo.playWhenReady = !exo.playWhenReady }
        st.seek = { ms -> exo.seekTo(ms) }
        onDispose {
            exo.removeListener(l)
            st.toggle = null
            st.seek = null
            exo.release()
        }
    }

    LaunchedEffect(exo) {
        while (true) {
            st.pos = exo.currentPosition
            st.dur = exo.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    LaunchedEffect(ch.url, st.attempt) {
        st.error = null
        st.buffering = true
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ch.userAgent ?: DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        ch.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val dsf = DefaultDataSource.Factory(ctx, http)
        val item = MediaItem.fromUri(ch.url)
        val source = if (st.forceHls) {
            HlsMediaSource.Factory(dsf).createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(dsf).createMediaSource(item)
        }
        exo.setMediaSource(source)
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
        update = { it.resizeMode = EXO_RESIZE[st.resizeIdx] },
    )
}
