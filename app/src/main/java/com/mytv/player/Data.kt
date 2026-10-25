package com.mytv.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Kind { LIVE, MOVIE, SERIES, RADIO }

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val userAgent: String?,
    val referrer: String?,
    val kind: Kind = Kind.LIVE,
    val series: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val tvgId: String? = null,
    val tvgName: String? = null,
)

data class Playlist(
    val id: String,
    val name: String,
    val url: String?, // null = imported local file
    val updated: Long,
    val epg: String? = null, // optional XMLTV override; null = auto-detect from provider
)

object M3uParser {
    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
    private val epRegex = Regex("""(?i)\bS(\d{1,2})\s*[ ._-]?\s*E(\d{1,3})\b""")
    private val videoExt = listOf(".mkv", ".mp4", ".avi", ".mov", ".m4v", ".wmv", ".flv")
    private val audioExt = listOf(".mp3", ".aac", ".ogg", ".m4a", ".pls", ".opus")

    private fun classify(name: String, url: String, group: String, type: String?, radio: String?): Kind {
        val g = group.lowercase()
        val u = url.lowercase().substringBefore('?')
        val t = type?.lowercase()
        if (t == "series") return Kind.SERIES
        if (t == "movie" || t == "movies" || t == "vod") return Kind.MOVIE
        if (radio?.lowercase() == "true" || g.contains("radio") || audioExt.any { u.endsWith(it) }) return Kind.RADIO
        if (u.contains("/series/") || g.contains("series") || g.contains("tv show") ||
            g.contains("episode") || epRegex.containsMatchIn(name)
        ) return Kind.SERIES
        if (u.contains("/movie/") || u.contains("/movies/") || u.contains("/vod/") ||
            g.contains("movie") || g.contains("film") || g.contains("vod") || g.contains("cinema") ||
            videoExt.any { u.endsWith(it) }
        ) return Kind.MOVIE
        return Kind.LIVE
    }

    fun parseFile(f: File): List<Channel> = f.useLines { parse(it) }

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

    fun parse(lines: Sequence<String>): List<Channel> {
        val out = ArrayList<Channel>()
        var name = ""
        var logo: String? = null
        var group: String? = null
        var ua: String? = null
        var ref: String? = null
        var tvgType: String? = null
        var radioAttr: String? = null
        var tvgId: String? = null
        var tvgName: String? = null

        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
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
                    val kind = classify(nm, url, gr, tvgType, radioAttr)
                    var seriesName: String? = null
                    var season = 0
                    var episode = 0
                    if (kind == Kind.SERIES) {
                        val m = epRegex.find(nm)
                        if (m != null) {
                            season = m.groupValues[1].toInt()
                            episode = m.groupValues[2].toInt()
                            seriesName = nm.removeRange(m.range)
                                .trim(' ', '-', '_', '.', ':', '|')
                                .ifBlank { gr }
                        } else {
                            seriesName = gr
                        }
                    }
                    out.add(
                        Channel(
                            name = nm,
                            url = url,
                            logo = logo,
                            group = gr,
                            userAgent = ua,
                            referrer = ref,
                            kind = kind,
                            series = seriesName,
                            season = season,
                            episode = episode,
                            tvgId = tvgId,
                            tvgName = tvgName,
                        )
                    )
                }
                name = ""; logo = null; group = null; ua = null; ref = null
                tvgType = null; radioAttr = null; tvgId = null; tvgName = null
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

    fun repo(): String = p.getString("repo", null) ?: BuildConfig.REPO
    fun saveRepo(r: String) = p.edit().putString("repo", r).apply()
}
