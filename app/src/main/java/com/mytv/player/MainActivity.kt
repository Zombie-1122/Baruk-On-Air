@file:OptIn(ExperimentalFoundationApi::class)

package com.mytv.player

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Color.White,
                    background = Bg,
                    surface = Bg,
                    onSurface = Txt,
                    onBackground = Txt,
                    surfaceVariant = Color(0xFF1B2433),
                    surfaceContainerHigh = Color(0xFF172131),
                    secondaryContainer = Sel,
                )
            ) {
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
    var seriesName by rememberSaveable { mutableStateOf("") }
    var playing by remember { mutableStateOf<PlayRequest?>(null) }

    fun play(list: List<Channel>, i: Int) {
        if (vm.engine == "external") openExternal(ctx, list[i]) else playing = PlayRequest(list, i)
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
        )
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
            "series" -> {
                BackHandler { screen = "home" }
                SeriesScreen(vm, seriesName, onBack = { screen = "home" }, onPlay = { l, i -> play(l, i) })
            }
            else -> HomeScreen(
                vm,
                onPlay = { l, i -> play(l, i) },
                onSettings = { screen = "settings" },
                onOpenSeries = { seriesName = it; screen = "series" },
            )
        }
    }
}

// ------------------------------------------------------------------ home

private const val FAV = "\u0001fav"

private val TABS = listOf(Kind.LIVE to "Live", Kind.MOVIE to "VOD", Kind.SERIES to "Series", Kind.RADIO to "Radio")

/** One line in the middle list: a channel/movie/station, or a whole series. */
private class Entry(val title: String, val sub: String, val logo: String?, val ch: Channel?, val series: String?)

@Composable
fun HomeScreen(
    vm: MainVm,
    onPlay: (List<Channel>, Int) -> Unit,
    onSettings: () -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var cat by rememberSaveable { mutableStateOf("") }
    var sel by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val snack = remember { SnackbarHostState() }
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    val kind = TABS[tab].first

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
    LaunchedEffect(tab, cat, query) { sel = 0 }
    BackHandler(enabled = searching) { searching = false; query = "" }

    val ofKind = remember(vm.channels, kind) { vm.channels.filter { it.kind == kind } }
    val groups = remember(ofKind) { ofKind.map { it.group }.distinct() }
    val entries = remember(ofKind, vm.favorites, cat, query, kind) {
        val base = ofKind.filter {
            (cat.isEmpty() || (cat == FAV && it.url in vm.favorites) || it.group == cat) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
        if (kind == Kind.SERIES) {
            base.groupBy { it.series ?: it.group }.map { (n, eps) ->
                Entry(n, "${eps.size} episodes", eps.firstOrNull { it.logo != null }?.logo, null, n)
            }
        } else {
            base.map { Entry(it.name, it.group, it.logo, it, null) }
        }
    }
    val chans = remember(entries) { entries.mapNotNull { it.ch } }

    fun activate(i: Int) {
        val e = entries.getOrNull(i) ?: return
        val s = e.series
        if (s != null) onOpenSeries(s) else onPlay(chans, i)
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
                if (searching) {
                    SearchField(query, { query = it }, Modifier.weight(1f))
                } else {
                    Text(
                        "BarukOnAir",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    if (wide) {
                        Spacer(Modifier.width(12.dp))
                        Tabs(tab) { tab = it; cat = "" }
                    }
                    Spacer(Modifier.weight(1f))
                }
                if (searching && wide) Tabs(tab) { tab = it; cat = "" }
                IconBtn(
                    if (searching) Icons.Default.Close else Icons.Default.Search,
                    "Search",
                    { if (searching) query = ""; searching = !searching },
                )
                IconBtn(Icons.Default.Refresh, "Update playlists and guide", { vm.refreshAll() })
                IconBtn(Icons.Default.Settings, "Settings", onSettings, badge = vm.update != null)
            }
            if (!wide) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                ) { Tabs(tab) { tab = it; cat = "" } }
            }
            if (vm.busy || vm.epgBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Accent, trackColor = Color.Transparent)
            } else {
                Spacer(Modifier.height(2.dp))
            }

            // ---- body ----
            if (vm.channels.isEmpty()) {
                EmptyState(vm.busy, onSettings)
            } else if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        Modifier.weight(0.22f).fillMaxHeight(),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        item { CategoryItem("All", cat.isEmpty()) { cat = "" } }
                        item { CategoryItem("Favorites", cat == FAV) { cat = FAV } }
                        items(groups, key = { it }) { g -> CategoryItem(g, cat == g) { cat = g } }
                    }
                    VDivider()
                    if (entries.isEmpty()) {
                        Box(Modifier.weight(0.78f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Text(if (cat == FAV) "No favorites here yet" else "Nothing found", color = Dim)
                        }
                    } else {
                        LazyColumn(
                            Modifier.weight(0.40f).fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            itemsIndexed(entries) { i, e ->
                                val rowCh = e.ch
                                val cur = if (kind == Kind.LIVE && rowCh != null) vm.epg.of(rowCh).nowAt(now) else null
                                ListRow(
                                    e = e,
                                    selected = sel == i,
                                    subtitle = if (kind == Kind.LIVE) (cur?.title ?: "No programme info") else e.sub,
                                    progress = cur?.let { (now - it.start).toFloat() / (it.stop - it.start).coerceAtLeast(1) },
                                    fav = e.ch?.url in vm.favorites,
                                    showHeart = false,
                                    onFocus = { if (it) sel = i },
                                    onClick = { if (sel == i) activate(i) else sel = i },
                                    onLong = { e.ch?.let { vm.toggleFavorite(it) } },
                                    onFav = {},
                                )
                            }
                        }
                        VDivider()
                        val picked = entries.getOrNull(sel.coerceIn(0, entries.lastIndex))
                        val pickedCh = picked?.ch
                        Detail(
                            e = picked,
                            kind = kind,
                            progs = if (pickedCh != null && kind == Kind.LIVE) vm.epg.of(pickedCh) else emptyList(),
                            now = now,
                            fav = pickedCh?.url in vm.favorites,
                            onFav = { pickedCh?.let { vm.toggleFavorite(it) } },
                            onOpen = { activate(sel.coerceIn(0, entries.lastIndex)) },
                            modifier = Modifier.weight(0.38f).fillMaxHeight(),
                        )
                    }
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Chip("All", cat.isEmpty()) { cat = "" } }
                    item { Chip("Favorites", cat == FAV) { cat = FAV } }
                    items(groups, key = { it }) { g -> Chip(g, cat == g) { cat = g } }
                }
                if (entries.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(if (cat == FAV) "No favorites here yet" else "Nothing found", color = Dim)
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        itemsIndexed(entries) { i, e ->
                            val rowCh = e.ch
                            val cur = if (kind == Kind.LIVE && rowCh != null) vm.epg.of(rowCh).nowAt(now) else null
                            ListRow(
                                e = e,
                                selected = false,
                                subtitle = if (kind == Kind.LIVE) (cur?.title ?: "No programme info") else e.sub,
                                progress = cur?.let { (now - it.start).toFloat() / (it.stop - it.start).coerceAtLeast(1) },
                                fav = e.ch?.url in vm.favorites,
                                showHeart = e.ch != null,
                                onFocus = {},
                                onClick = { activate(i) },
                                onLong = { e.ch?.let { vm.toggleFavorite(it) } },
                                onFav = { e.ch?.let { vm.toggleFavorite(it) } },
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Tabs(current: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TABS.forEachIndexed { i, t -> Pill(t.second, current == i, { onSelect(i) }) }
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
            .background(Color(0x33FFFFFF))
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
            color = if (selected) Color.White else Txt.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .height(32.dp)
            .clip(shape)
            .background(if (selected) Sel else Color(0x22FFFFFF))
            .then(if (selected) Modifier.border(1.dp, SelBorder, shape) else Modifier)
            .tvFocus(shape)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, maxLines = 1, color = if (selected) Color.White else Txt.copy(alpha = 0.85f))
    }
}

@Composable
private fun ListRow(
    e: Entry,
    selected: Boolean,
    subtitle: String,
    progress: Float?,
    fav: Boolean,
    showHeart: Boolean,
    onFocus: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLong: () -> Unit,
    onFav: () -> Unit,
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
        Logo(e.logo, e.title, 48.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                e.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                color = Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (progress != null) Bar(progress, Modifier.padding(top = 6.dp))
        }
        if (showHeart) {
            IconBtn(
                if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                "Favorite",
                onFav,
                tint = if (fav) Heart else Dim,
            )
        } else if (fav) {
            Icon(Icons.Default.Favorite, null, tint = Heart, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun Detail(
    e: Entry?,
    kind: Kind,
    progs: List<Programme>,
    now: Long,
    fav: Boolean,
    onFav: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 14.dp).verticalScroll(rememberScrollState())) {
        if (e != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (kind == Kind.LIVE || kind == Kind.RADIO) Logo(e.logo, e.title, 64.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        e.title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(e.ch?.group ?: e.sub, color = Dim, fontSize = 13.sp, maxLines = 1)
                }
                if (e.ch != null) {
                    IconBtn(
                        if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Favorite",
                        onFav,
                        tint = if (fav) Heart else Dim,
                    )
                }
                Pill(if (e.series != null) "Open" else "Watch", true, onOpen)
            }

            if (kind == Kind.MOVIE || kind == Kind.SERIES) {
                Spacer(Modifier.height(16.dp))
                Logo(e.logo, e.title, 210.dp, fit = false, width = 140.dp)
            }

            if (kind == Kind.LIVE) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "EPG DETAILS  ${e.title}".uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                val cur = progs.nowAt(now)
                if (cur == null) {
                    Text("No programme info", color = Dim, fontSize = 14.sp)
                } else {
                    Text(cur.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("${hm(cur.start)} - ${hm(cur.stop)}", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    Bar((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1), Modifier.padding(vertical = 8.dp))
                    if (cur.desc.isNotBlank()) {
                        Text(cur.desc, fontSize = 13.sp, color = Txt.copy(alpha = 0.8f), maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("UPCOMING", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = Dim)
                Spacer(Modifier.height(8.dp))
                val next = progs.upcoming(now, 6)
                if (next.isEmpty()) {
                    Text("No upcoming programmes", color = Dim, fontSize = 14.sp)
                } else {
                    for (p in next) {
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(hm(p.start), color = Dim, fontSize = 13.sp, modifier = Modifier.width(52.dp))
                            Text(p.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ series

@Composable
fun SeriesScreen(vm: MainVm, name: String, onBack: () -> Unit, onPlay: (List<Channel>, Int) -> Unit) {
    val eps = remember(vm.channels, name) {
        vm.channels
            .filter { it.kind == Kind.SERIES && (it.series ?: it.group) == name }
            .sortedWith(compareBy<Channel>({ it.season }, { it.episode }))
    }
    ScreenFrame(name, onBack) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            var last = -1
            eps.forEachIndexed { i, e ->
                if (e.season != last) {
                    last = e.season
                    if (e.season > 0) {
                        item {
                            Text(
                                "SEASON ${e.season}",
                                modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp),
                                color = Color(0xFF6FA8F0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp,
                            )
                        }
                    }
                }
                item {
                    ListRow(
                        e = Entry(e.name, e.group, e.logo, e, null),
                        selected = false,
                        subtitle = if (e.season > 0) "S${e.season} E${e.episode}" else e.group,
                        progress = null,
                        fav = e.url in vm.favorites,
                        showHeart = true,
                        onFocus = {},
                        onClick = { onPlay(eps, i) },
                        onLong = { vm.toggleFavorite(e) },
                        onFav = { vm.toggleFavorite(e) },
                    )
                }
            }
        }
    }
}
