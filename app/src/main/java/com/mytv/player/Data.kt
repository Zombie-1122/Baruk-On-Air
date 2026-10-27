package com.mytv.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Kind { LIVE, RADIO }

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val userAgent: String?,
    val referrer: String?,
    val kind: Kind = Kind.LIVE,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val source: String = "",   // playlist provider (playlist name)
    val number: Int = 0,       // tvg-chno, or position in the combined list
    val drmType: String? = null, // "clearkey" / "widevine" (from #KODIPROP)
    val drmKey: String? = null,
    val manifest: String? = null, // "dash" / "hls" (from #KODIPROP)
)

data class Playlist(
    val id: String,
    val name: String,
    val url: String?, // null = imported local file
    val updated: Long,
    val epg: String? = null, // optional XMLTV override; null = auto-detect from provider
)

enum class Sort(val id: String, val label: String, val short: String) {
    NUMBER("number", "Number", "Number"),
    NAME("name", "Name", "Name"),
    CATEGORY("category", "Category", "Category"),
    PROVIDER("provider", "Playlist provider", "Provider"),
    FREQUENCY("frequency", "Frequency (most watched)", "Frequency");

    companion object {
        fun of(id: String?): Sort = entries.firstOrNull { it.id == id } ?: NUMBER
    }
}

object M3uParser {
    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
    private val audioExt = listOf(".mp3", ".aac", ".ogg", ".m4a", ".pls", ".opus")

    private fun classify(url: String, group: String, type: String?, radio: String?): Kind {
        val g = group.lowercase()
        val u = url.lowercase().substringBefore('?')
        if (type?.lowercase() == "radio" || radio?.lowercase() == "true" ||
            g.contains("radio") || audioExt.any { u.endsWith(it) }
        ) return Kind.RADIO
        return Kind.LIVE
    }

    fun parseFile(f: File, source: String = "", firstNumber: Int = 1): List<Channel> =
        f.useLines { parse(it, source, firstNumber) }

    /** XMLTV guide links declared in the playlist header (url-tvg / x-tvg-url). */
    fun headerEpgUrls(f: File): List<String> = f.useLines { lines ->
        val first = lines.map { it.trim().removePrefix("\uFEFF") }.firstOrNull { it.isNotEmpty() }
            ?: return@useLines emptyList()
        if (!first.startsWith("#EXTM3U", ignoreCase = true)) return@useLines emptyList()
        val attrs = attrRegex.findAll(first).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        (attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"] ?: "")
            .split(',', ' ')
            .map { it.trim() }
            .filter { it.startsWith("http", ignoreCase = true) }
    }

    fun parse(lines: Sequence<String>, source: String = "", firstNumber: Int = 1): List<Channel> {
        val out = ArrayList<Channel>()
        var next = firstNumber
        var name = ""
        var logo: String? = null
        var group: String? = null
        var ua: String? = null
        var ref: String? = null
        var tvgType: String? = null
        var radioAttr: String? = null
        var tvgId: String? = null
        var tvgName: String? = null
        var chno = 0
        // #KODIPROP lines may come before OR after #EXTINF, so they are only cleared after the URL.
        var drmType: String? = null
        var drmKey: String? = null
        var manifest: String? = null

        for (raw in lines) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val q = line.lastIndexOf('"')
                val comma = line.indexOf(',', if (q > 0) q else 0)
                name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                val head = if (comma >= 0) line.substring(0, comma) else line
                val attrs = attrRegex.findAll(head)
                    .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                tvgType = attrs["tvg-type"]
                radioAttr = attrs["radio"]
                tvgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() }
                tvgName = attrs["tvg-name"]?.trim()?.takeIf { it.isNotEmpty() }
                chno = (attrs["tvg-chno"] ?: attrs["channel-number"])?.trim()?.toIntOrNull() ?: 0
                logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                if (name.isEmpty()) name = attrs["tvg-name"] ?: ""
            } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                if (group == null) group = line.substringAfter(':').trim().ifEmpty { null }
            } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                val kv = line.substringAfter(':')
                val k = kv.substringBefore('=').lowercase()
                val v = kv.substringAfter('=', "").trim()
                if (k == "http-user-agent") ua = v
                if (k == "http-referrer") ref = v
            } else if (line.startsWith("#KODIPROP:", ignoreCase = true)) {
                val kv = line.substringAfter(':')
                val k = kv.substringBefore('=').trim().lowercase()
                val v = kv.substringAfter('=', "").trim()
                when (k) {
                    "inputstream.adaptive.license_type" -> drmType = v.lowercase()
                    "inputstream.adaptive.license_key" -> drmKey = v
                    "inputstream.adaptive.manifest_type" -> manifest = v.lowercase()
                }
            } else if (line.startsWith("#")) {
                // other comment / tag: ignore
            } else {
                var url = line
                if (url.contains('|')) {
                    val parts = url.split('|', limit = 2)
                    url = parts[0]
                    for (h in parts[1].split('&')) {
                        val k = h.substringBefore('=').lowercase()
                        val v = h.substringAfter('=', "")
                        if (k == "user-agent") ua = v
                        if (k == "referer" || k == "referrer") ref = v
                    }
                }
                if (url.contains("://")) {
                    val nm = name.ifBlank { url }
                    val gr = group ?: "Uncategorized"
                    out.add(
                        Channel(
                            name = nm,
                            url = url,
                            logo = logo,
                            group = gr,
                            userAgent = ua,
                            referrer = ref,
                            kind = classify(url, gr, tvgType, radioAttr),
                            tvgId = tvgId,
                            tvgName = tvgName,
                            source = source,
                            number = if (chno > 0) chno else next,
                            drmType = drmType,
                            drmKey = drmKey,
                            manifest = manifest,
                        )
                    )
                    next++
                }
                name = ""; logo = null; group = null; ua = null; ref = null
                tvgType = null; radioAttr = null; tvgId = null; tvgName = null; chno = 0
                drmType = null; drmKey = null; manifest = null
            }
        }
        return out
    }
}

class Store(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("mytv", Context.MODE_PRIVATE)

    fun file(id: String): File = File(ctx.filesDir, "pl_$id.m3u")

    fun playlists(): List<Playlist> {
        val a = JSONArray(p.getString("playlists", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Playlist(
                id = o.getString("id"),
                name = o.getString("name"),
                url = o.optString("url", "").ifBlank { null },
                updated = o.optLong("updated", 0L),
                epg = o.optString("epg", "").ifBlank { null },
            )
        }
    }

    fun savePlaylists(list: List<Playlist>) {
        val a = JSONArray()
        for (pl in list) {
            a.put(
                JSONObject()
                    .put("id", pl.id)
                    .put("name", pl.name)
                    .put("url", pl.url ?: "")
                    .put("updated", pl.updated)
                    .put("epg", pl.epg ?: "")
            )
        }
        p.edit().putString("playlists", a.toString()).apply()
    }

    fun favorites(): Set<String> = p.getStringSet("favs", emptySet())!!.toSet()
    fun saveFavorites(s: Set<String>) = p.edit().putStringSet("favs", s).apply()

    fun refreshHours(): Int = p.getInt("refreshHours", 12)
    fun saveRefreshHours(h: Int) = p.edit().putInt("refreshHours", h).apply()

    // "vlc" was removed to keep the app small; old setting falls back to the built-in player.
    fun engine(): String = if (p.getString("engine", "exo") == "external") "external" else "exo"
    fun saveEngine(e: String) = p.edit().putString("engine", e).apply()

    fun plays(): Map<String, Int> {
        val o = JSONObject(p.getString("plays", "{}") ?: "{}")
        return o.keys().asSequence().associateWith { o.optInt(it) }
    }

    fun savePlays(m: Map<String, Int>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        p.edit().putString("plays", o.toString()).apply()
    }

    fun heights(): Map<String, Int> {
        val o = JSONObject(p.getString("heights", "{}") ?: "{}")
        return o.keys().asSequence().associateWith { o.optInt(it) }
    }

    fun saveHeights(m: Map<String, Int>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        p.edit().putString("heights", o.toString()).apply()
    }

    fun sort(): Sort = Sort.of(p.getString("sort", null))
    fun saveSort(s: Sort) = p.edit().putString("sort", s.id).apply()

    fun repo(): String = p.getString("repo", null) ?: BuildConfig.REPO
    fun saveRepo(r: String) = p.edit().putString("repo", r).apply()
}
