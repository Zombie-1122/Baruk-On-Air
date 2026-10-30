@file:OptIn(ExperimentalFoundationApi::class)

package com.mytv.player

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    // leaving the app while live TV plays: float the picture instead of stopping
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Pip.playing && !Pip.active) Pip.enter(this)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Pip.active = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scheme = if (Pal.dark) darkColorScheme(
                primary = Accent,
                onPrimary = OnAccent,
                background = Bg,
                surface = Bg,
                onSurface = Txt,
                onBackground = Txt,
                surfaceVariant = Color(0xFF1E1E1E),
                surfaceContainer = Color(0xFF181818),
                surfaceContainerHigh = Color(0xFF181818),
                secondaryContainer = Sel,
            ) else lightColorScheme(
                primary = Accent,
                onPrimary = OnAccent,
                background = Bg,
                surface = Bg,
                onSurface = Txt,
                onBackground = Txt,
                surfaceVariant = Color(0xFFEAE6DA),
                surfaceContainer = Color.White,
                surfaceContainerHigh = Color.White,
                secondaryContainer = Sel,
            )
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalContentColor provides Txt) {
                    App()
                }
            }
        }
    }
}

class PlayRequest(val list: List<Channel>, val index: Int)

fun openExternal(ctx: Context, ch: Channel) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(ch.url), if (ch.kind == Kind.RADIO) "audio/*" else "video/*")
        .putExtra("title", ch.name)
    try {
        ctx.startActivity(Intent.createChooser(intent, "Play with"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No external player found", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun App(vm: MainVm = viewModel()) {
    val ctx = LocalContext.current
    var screen by rememberSaveable { mutableStateOf("home") }
    var playing by remember { mutableStateOf<PlayRequest?>(null) }

    fun play(list: List<Channel>, i: Int) {
        if (vm.engine == "external") {
            openExternal(ctx, list[i])
            vm.watched(list[i])
        } else {
            playing = PlayRequest(list, i)
        }
    }

    val p = playing
    if (p != null) {
        BackHandler { playing = null }
        PlayerScreen(
            channels = p.list,
            startIndex = p.index,
            favorites = vm.favorites,
            epg = vm.epg,
            onFav = { vm.toggleFavorite(it) },
            onClose = { playing = null },
            onExternal = { openExternal(ctx, it) },
            onTune = { vm.selUrl = it.url },
            onWatched = { vm.watched(it) },
            onDead = { vm.markDead(it) },
            onAlive = { vm.markAlive(it) },
            onHeight = { c, h -> vm.learnHeight(c, h) },
        )
        return
    }

    if (!vm.welcomed) {
        AppBackground { WelcomeScreen { vm.finishWelcome() } }
        return
    }

    AppBackground {
        when (screen) {
            "settings" -> {
                BackHandler { screen = "home" }
                SettingsScreen(vm, onBack = { screen = "home" }, onPlaylists = { screen = "playlists" })
            }
            "playlists" -> {
                BackHandler { screen = "settings" }
                PlaylistsScreen(vm, onBack = { screen = "settings" })
            }
            else -> HomeScreen(
                vm,
                onPlay = { l, i -> play(l, i) },
                onSettings = { screen = "settings" },
            )
        }
    }
}

// ------------------------------------------------------------------ home

private const val NEW = "\u0001new"
private const val NEW_MS = 7L * 24 * 3600 * 1000 // "recently" = the last 7 days

private val TABS = listOf(Kind.LIVE to "Live", Kind.RADIO to "Radio")

@Composable
fun HomeScreen(
    vm: MainVm,
    onPlay: (List<Channel>, Int) -> Unit,
    onSettings: () -> Unit,
) {
    var query by vm::query
    var searching by vm::searching
    var tab by vm::tab
    var cat by vm::cat
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val snack = remember { SnackbarHostState() }
    val showTitle = LocalConfiguration.current.screenWidthDp >= 760
    val kind = TABS[tab].first
    val sort = vm.sort

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snack.showSnackbar(it)
            vm.message = null
        }
    }
    // jump back to the top only when the user changes tab/category/search/sort (not when returning from the player)
    val filterKey = listOf(tab, cat, query, sort)
    var seenKey by remember { mutableStateOf(filterKey) }
    LaunchedEffect(filterKey) {
        if (filterKey != seenKey) {
            seenKey = filterKey
            vm.listState.scrollToItem(0)
        }
    }
    BackHandler(enabled = searching) { searching = false; query = "" }

    val ofKind = remember(vm.channels, kind) { vm.channels.filter { it.kind == kind } }
    val groups = remember(ofKind, sort) {
        val g = ofKind.map { it.group }.distinct()
        if (sort == Sort.CATEGORY) g.sortedWith(String.CASE_INSENSITIVE_ORDER) else g
    }
    val entries = remember(ofKind, cat, query, sort, vm.plays, vm.seen) {
        val cutoff = System.currentTimeMillis() - NEW_MS
        val base = ofKind.filter {
            (cat.isEmpty() || (cat == NEW && vm.addedAt(it) > cutoff) || it.group == cat) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
        // every sort is stable, so ties keep the playlist order
        if (cat == NEW) base.sortedByDescending { vm.addedAt(it) } else when (sort) {
            Sort.NUMBER -> base.sortedBy { if (it.number > 0) it.number else Int.MAX_VALUE }
            Sort.NAME -> base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            Sort.CATEGORY -> base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.group })
            Sort.PROVIDER -> base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.source })
            Sort.FREQUENCY -> base.sortedByDescending { vm.plays[it.url] ?: 0 }
        }
    }

    // selected row is remembered by channel (not by position), so it survives re-sorting and the player
    val sel = entries.indexOfFirst { it.url == vm.selUrl }.let { if (it < 0) 0 else it }

    // coming back from the player: make sure the last channel is on screen
    LaunchedEffect(Unit) {
        val i = entries.indexOfFirst { it.url == vm.selUrl }
        val vis = vm.listState.layoutInfo.visibleItemsInfo
        if (i >= 0 && (vis.isEmpty() || i < vis.first().index || i > vis.last().index)) {
            vm.listState.scrollToItem((i - 1).coerceAtLeast(0))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- top bar ----
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(painterResource(R.drawable.logo_mark), null, Modifier.size(38.dp))
                if (showTitle) {
                    Text(
                        "BarukOnAir",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Tabs(tab) { tab = it; cat = "" }
                if (searching) {
                    SearchField(query, { query = it }, Modifier.weight(1f).padding(start = 8.dp))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                ClockText(Modifier.padding(horizontal = 6.dp))
                IconBtn(
                    if (searching) Icons.Default.Close else Icons.Default.Search,
                    "Search",
                    { if (searching) query = ""; searching = !searching },
                )
                IconBtn(Icons.Default.Refresh, "Update playlists and guide", { vm.refreshAll() })
                IconBtn(Icons.Default.Settings, "Settings", onSettings, badge = vm.update != null)
            }
            if (vm.busy || vm.epgBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Accent, trackColor = Color.Transparent)
            } else {
                Spacer(Modifier.height(2.dp))
            }

            // ---- body ----
            if (vm.channels.isEmpty()) {
                EmptyState(vm.busy, onSettings)
            } else {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        Modifier.weight(0.22f).fillMaxHeight(),
                        state = vm.catState,
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        item { CategoryItem("All", cat.isEmpty()) { cat = "" } }
                        item { CategoryItem("Recently added", cat == NEW) { cat = NEW } }
                        items(groups, key = { it }) { g -> CategoryItem(g, cat == g) { cat = g } }
                    }
                    VDivider()
                    Column(Modifier.weight(0.40f).fillMaxHeight()) {
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${entries.size} " + if (kind == Kind.RADIO) "stations" else "channels",
                                color = Dim,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            SortMenu(sort) { vm.chooseSort(it) }
                        }
                        if (entries.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(if (cat == NEW) "Nothing new in the last 7 days" else "Nothing found", color = Dim)
                            }
                        } else {
                            LazyColumn(
                                Modifier.weight(1f).fillMaxWidth(),
                                state = vm.listState,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                itemsIndexed(entries) { i, ch ->
                                    val cur = if (kind == Kind.LIVE) vm.epg.of(ch).nowAt(now) else null
                                    ListRow(
                                        ch = ch,
                                        selected = sel == i,
                                        number = if (sort == Sort.NUMBER) ch.number else 0,
                                        quality = vm.qualityOf(ch),
                                        subtitle = if (kind == Kind.LIVE) (cur?.title ?: "No programme info") else ch.group,
                                        progress = cur?.let { (now - it.start).toFloat() / (it.stop - it.start).coerceAtLeast(1) },
                                        fav = ch.url in vm.favorites,
                                        dead = ch.url in vm.dead,
                                        onFocus = { if (it) vm.selUrl = ch.url },
                                        onClick = { if (sel == i) onPlay(entries, i) else vm.selUrl = ch.url },
                                        onLong = { vm.toggleFavorite(ch) },
                                    )
                                }
                            }
                        }
                    }
                    VDivider()
                    val picked = entries.getOrNull(sel)
                    Detail(
                        ch = picked,
                        quality = picked?.let { vm.qualityOf(it) },
                        kind = kind,
                        progs = if (picked != null && kind == Kind.LIVE) vm.epg.of(picked) else emptyList(),
                        now = now,
                        fav = picked != null && picked.url in vm.favorites,
                        dead = picked != null && picked.url in vm.dead,
                        onFav = { picked?.let { vm.toggleFavorite(it) } },
                        onOpen = { if (picked != null) onPlay(entries, entries.indexOf(picked)) },
                        modifier = Modifier.weight(0.38f).fillMaxHeight(),
                    )
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Tabs(current: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 8.dp)) {
        TABS.forEachIndexed { i, t -> Pill(t.second, current == i, { onSelect(i) }) }
    }
}

@Composable
private fun SortMenu(current: Sort, onPick: (Sort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill("Sort: ${current.short}", false, { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (s in Sort.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            s.label,
                            color = if (s == current) AccentLight else Txt,
                            fontWeight = if (s == current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { onPick(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(Veil)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text("Search", color = Dim, fontSize = 14.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Txt, fontSize = 14.sp),
            cursorBrush = SolidColor(Txt),
            modifier = Modifier.fillMaxWidth().focusRequester(fr),
        )
    }
}

@Composable
private fun EmptyState(busy: Boolean, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (busy) "Loading playlist..." else "No channels yet", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (!busy) {
            Text(
                "Add your M3U or Xtream playlist to start.",
                color = Dim,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            Pill("Open settings", true, onSettings)
        }
    }
}

@Composable
private fun VDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Hair))
}

@Composable
private fun CategoryItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(48.dp)
            .clip(shape)
            .background(if (selected) Sel else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, SelBorder, shape) else Modifier)
            .tvFocus(shape)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label.uppercase(),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            color = if (selected) OnSel else Txt.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ListRow(
    ch: Channel,
    selected: Boolean,
    number: Int,
    quality: String?,
    subtitle: String,
    progress: Float?,
    fav: Boolean,
    dead: Boolean,
    onFocus: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLong: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(if (selected) Sel else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, SelBorder, shape) else Modifier)
            .tvFocus(shape, onFocus)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number > 0) {
            Text(
                number.toString(),
                color = Dim,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.width(34.dp),
            )
        }
        Logo(ch.logo, ch.name, 60.dp, width = 92.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ch.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (dead) Txt.copy(alpha = 0.5f) else Txt,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (dead) {
                    Spacer(Modifier.width(8.dp))
                    DeadBadge()
                }
                if (quality != null && !dead) {
                    Spacer(Modifier.width(8.dp))
                    QualityBadge(quality)
                }
            }
            Text(
                subtitle,
                fontSize = 11.sp,
                color = if (dead) Dim.copy(alpha = 0.6f) else Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (progress != null) Bar(progress, Modifier.padding(top = 6.dp))
        }
        if (fav) Icon(Icons.Default.Favorite, null, tint = Heart, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Detail(
    ch: Channel?,
    quality: String?,
    kind: Kind,
    progs: List<Programme>,
    now: Long,
    fav: Boolean,
    dead: Boolean,
    onFav: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 14.dp).verticalScroll(rememberScrollState())) {
        if (ch != null) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Logo(ch.logo, ch.name, 92.dp, width = 128.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        ch.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (dead) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            DeadBadge()
                            Spacer(Modifier.width(8.dp))
                            Text("Last attempt failed", color = Dim, fontSize = 12.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        if (quality != null) {
                            QualityBadge(quality)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (ch.source.isNotEmpty()) "${ch.group}  |  ${ch.source}" else ch.group,
                            color = Dim,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        Pill("Watch", true, onOpen)
                        IconBtn(
                            if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "Favorite",
                            onFav,
                            tint = if (fav) Heart else Dim,
                        )
                    }
                }
            }

            if (kind == Kind.LIVE) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "EPG DETAILS  ${ch.name}".uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                val cur = progs.nowAt(now)
                if (cur == null) {
                    Text("No programme info", color = Dim, fontSize = 12.sp)
                } else {
                    Text(cur.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("${hm(cur.start)} - ${hm(cur.stop)}", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    Bar((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1), Modifier.padding(vertical = 6.dp))
                    if (cur.desc.isNotBlank()) {
                        Text(cur.desc, fontSize = 11.5.sp, color = Txt.copy(alpha = 0.8f), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("UPCOMING", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = Dim)
                Spacer(Modifier.height(6.dp))
                val next = progs.upcoming(now, 6)
                if (next.isEmpty()) {
                    Text("No upcoming programmes", color = Dim, fontSize = 12.sp)
                } else {
                    for (p in next) {
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(hm(p.start), color = Dim, fontSize = 11.5.sp, modifier = Modifier.width(48.dp))
                            Text(p.title, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
