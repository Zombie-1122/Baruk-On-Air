package com.mytv.player

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

const val DEFAULT_UA = "VLC/3.0.18 LibVLC/3.0.18"

class MainVm(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    var playlists by mutableStateOf(store.playlists())
        private set
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var favorites by mutableStateOf(store.favorites())
        private set
    var refreshHours by mutableIntStateOf(store.refreshHours())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    var engine by mutableStateOf(store.engine())
        private set
    var repo by mutableStateOf(store.repo())
        private set
    var update by mutableStateOf<UpdateInfo?>(null)
        private set
    var updateStatus by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set

    // Home-screen position. Kept here (not in the screen) because the home screen is removed
    // while a channel plays; this way you come back to the same tab, category, search and spot.
    var tab by mutableIntStateOf(0)
    var cat by mutableStateOf("")
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var selUrl by mutableStateOf<String?>(null)
    val listState = LazyListState()
    val catState = LazyListState()

    var sort by mutableStateOf(store.sort())
        private set
    var plays by mutableStateOf(store.plays())
        private set
    var heights by mutableStateOf(store.heights())
        private set

    var epg by mutableStateOf(EpgIndex.EMPTY)
        private set
    var epgBusy by mutableStateOf(false)
        private set
    var epgStatus by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            loadCache()
            autoRefresh()
            refreshEpgIfStale()
        }
        checkUpdate()
    }

    // ---- app update (GitHub releases) ----

    fun chooseEngine(e: String) {
        engine = e
        store.saveEngine(e)
    }

    fun chooseSort(s: Sort) {
        sort = s
        store.saveSort(s)
    }

    /** Highest video height seen for this channel while playing; remembered so the list can show SD/HD/FHD. */
    fun learnHeight(c: Channel, h: Int) {
        if (h > (heights[c.url] ?: 0)) {
            heights = heights + (c.url to h)
            store.saveHeights(heights)
        }
    }

    /** SD / HD / FHD / 4K: from what the stream really offered, else guessed from the channel name. */
    fun qualityOf(c: Channel): String? = qualityLabel(heights[c.url] ?: 0) ?: nameQuality(c.name)

    /** Called after a channel has actually been watched for a few seconds (drives "Frequency" sort). */
    fun watched(c: Channel) {
        plays = plays + (c.url to ((plays[c.url] ?: 0) + 1))
        store.savePlays(plays)
    }

    fun checkUpdate() {
        val r = repo.trim()
        if (!r.contains('/')) {
            updateStatus = "Update check is not available in this build"
            return
        }
        viewModelScope.launch {
            checking = true
            try {
                val info = withContext(Dispatchers.IO) { fetchLatestRelease(http, r) }
                if (info.build > BuildConfig.VERSION_CODE) {
                    update = info
                    updateStatus = "New update available"
                } else {
                    update = null
                    updateStatus = "You are up to date (build ${BuildConfig.VERSION_CODE})"
                }
            } catch (e: Exception) {
                updateStatus = "Update check failed: ${e.message ?: e.javaClass.simpleName}"
            }
            checking = false
        }
    }

    // ---- loading ----

    private suspend fun loadCache() {
        val pls = playlists
        val all = withContext(Dispatchers.IO) {
            val acc = ArrayList<Channel>()
            for (pl in pls) {
                val f = store.file(pl.id)
                if (f.exists()) {
                    runCatching { M3uParser.parseFile(f, pl.name, acc.size + 1) }.getOrNull()?.let { acc.addAll(it) }
                }
            }
            acc
        }
        channels = all
        loadEpgCache()
    }

    // ---- programme guide (EPG) ----

    private suspend fun loadEpgCache() {
        val app = getApplication<Application>()
        val files = playlists.map { Epg.file(app, it.id) }
        epg = withContext(Dispatchers.IO) { Epg.load(files) }
    }

    private suspend fun refreshEpgIfStale() {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        val due = playlists.filter {
            val f = Epg.file(app, it.id)
            !f.exists() || now - f.lastModified() > Epg.STALE_MS
        }
        if (due.isNotEmpty()) refreshEpg(due, manual = false)
    }

    fun refreshEpgNow() {
        if (playlists.isEmpty()) {
            message = "Add a playlist first"
            return
        }
        viewModelScope.launch { refreshEpg(playlists, manual = true) }
    }

    private suspend fun refreshEpg(list: List<Playlist>, manual: Boolean) {
        if (epgBusy) return
        epgBusy = true
        val app = getApplication<Application>()
        val live = channels.filter { it.kind == Kind.LIVE }
        var programmes = 0
        var ok = 0
        val errors = ArrayList<String>()
        for (pl in list) {
            try {
                programmes += withContext(Dispatchers.IO) {
                    val urls = Epg.candidateUrls(pl, store.file(pl.id))
                    Epg.refresh(http, urls, live, File(app.cacheDir, "epg.tmp"), Epg.file(app, pl.id))
                }
                ok++
            } catch (e: Exception) {
                errors.add("${pl.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        loadEpgCache()
        epgStatus = when {
            ok > 0 && errors.isEmpty() -> "Guide updated - ${epg.channelCount} channels, $programmes programmes"
            ok > 0 -> "Guide partly updated - " + errors.joinToString("; ")
            else -> "Guide failed - " + errors.joinToString("; ")
        }
        if (manual) message = epgStatus
        epgBusy = false
    }

    private suspend fun autoRefresh() {
        if (refreshHours <= 0) return
        val limit = refreshHours * 3_600_000L
        val now = System.currentTimeMillis()
        val due = playlists.filter { it.url != null && now - it.updated > limit }
        if (due.isNotEmpty()) refresh(due)
    }

    fun refreshAll() {
        val list = playlists.filter { it.url != null }
        if (list.isEmpty()) {
            if (playlists.isEmpty()) message = "Add a playlist first" else refreshEpgNow()
            return
        }
        viewModelScope.launch { refresh(list) }
    }

    fun refreshOne(pl: Playlist) {
        viewModelScope.launch { refresh(listOf(pl)) }
    }

    private suspend fun refresh(list: List<Playlist>) {
        busy = true
        var ok = 0
        val errors = ArrayList<String>()
        for (pl in list) {
            try {
                withContext(Dispatchers.IO) { download(pl) }
                ok++
                setPlaylists(playlists.map {
                    if (it.id == pl.id) it.copy(updated = System.currentTimeMillis()) else it
                })
            } catch (e: Exception) {
                errors.add("${pl.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        loadCache()
        busy = false
        message = if (errors.isEmpty()) "Updated $ok playlist(s)" else "Failed - " + errors.joinToString("; ")
        if (ok > 0) refreshEpg(list, manual = false)
    }

    /** Downloads to a temp file first, so a failed update never destroys the working copy. */
    private fun download(pl: Playlist) {
        val url = pl.url ?: return
        val req = Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build()
        val tmp = File(getApplication<Application>().cacheDir, "dl.tmp")
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("Empty response")
            body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        }
        val n = M3uParser.parseFile(tmp).size
        if (n == 0) {
            tmp.delete()
            error("No channels found in file")
        }
        tmp.copyTo(store.file(pl.id), overwrite = true)
        tmp.delete()
    }

    // ---- playlists ----

    @JvmName("storePlaylists")
    private fun setPlaylists(list: List<Playlist>) {
        playlists = list
        store.savePlaylists(list)
    }

    /** existing == null -> add new, otherwise edit. */
    fun savePlaylist(existing: Playlist?, name: String, url: String, epgUrl: String) {
        val epg = epgUrl.trim().ifEmpty { null }
        val pl = existing?.copy(name = name, url = url, updated = 0L, epg = epg)
            ?: Playlist(UUID.randomUUID().toString(), name, url, 0L, epg)
        setPlaylists(if (existing == null) playlists + pl else playlists.map { if (it.id == pl.id) pl else it })
        viewModelScope.launch { refresh(listOf(pl)) }
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Imported file"
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val f = store.file(id)
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: error("Cannot open file")
                    input.use { i -> f.outputStream().use { i.copyTo(it) } }
                    if (M3uParser.parseFile(f).isEmpty()) {
                        f.delete()
                        error("No channels found in file")
                    }
                }
                setPlaylists(playlists + Playlist(id, name, null, System.currentTimeMillis()))
                loadCache()
                message = "Imported $name"
            } catch (e: Exception) {
                message = "Import failed: ${e.message}"
            }
            busy = false
        }
    }

    fun deletePlaylist(pl: Playlist) {
        store.file(pl.id).delete()
        Epg.file(getApplication<Application>(), pl.id).delete()
        setPlaylists(playlists.filter { it.id != pl.id })
        viewModelScope.launch { loadCache() }
    }

    @JvmName("changeRefreshHours")
    fun setRefreshHours(h: Int) {
        refreshHours = h
        store.saveRefreshHours(h)
    }

    // ---- favorites ----

    fun toggleFavorite(c: Channel) {
        favorites = if (c.url in favorites) favorites - c.url else favorites + c.url
        store.saveFavorites(favorites)
    }
}
