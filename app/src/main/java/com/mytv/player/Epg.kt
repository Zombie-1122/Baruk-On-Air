package com.mytv.player

import android.content.Context
import android.util.Xml
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class Programme(val start: Long, val stop: Long, val title: String, val desc: String)

/** Lower-case letters/digits only, so "BBC One HD" and "bbc.one-hd" meet in the middle. */
fun epgNorm(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
    return sb.toString()
}

fun List<Programme>.nowAt(t: Long): Programme? = firstOrNull { t >= it.start && t < it.stop }
fun List<Programme>.upcoming(t: Long, n: Int): List<Programme> = filter { it.start > t }.take(n)

class EpgIndex(
    private val byId: Map<String, List<Programme>>,
    private val nameToId: Map<String, String>,
) {
    val isEmpty: Boolean get() = byId.isEmpty()
    val channelCount: Int get() = byId.size

    fun of(ch: Channel): List<Programme> {
        ch.tvgId?.lowercase()?.let { id -> byId[id]?.let { return it } }
        for (n in listOf(ch.tvgName, ch.name)) {
            if (n == null) continue
            val k = epgNorm(n)
            if (k.isEmpty()) continue
            byId[nameToId[k] ?: k]?.let { return it }
        }
        return emptyList()
    }

    companion object {
        val EMPTY = EpgIndex(emptyMap(), emptyMap())
    }
}

/**
 * Programme guide (XMLTV) taken from the IPTV provider.
 * Link comes from: the playlist's own EPG field, the Xtream login (xmltv.php),
 * or the url-tvg / x-tvg-url attribute in the M3U header.
 * Only live channels in the playlist are kept, for a short time window, to stay light on memory.
 */
object Epg {
    private const val PAST_MS = 3 * 3_600_000L
    private const val FUTURE_MS = 36 * 3_600_000L
    private const val DESC_HORIZON_MS = 6 * 3_600_000L
    private const val MAX_PROGRAMMES = 250_000
    const val STALE_MS = 6 * 3_600_000L

    fun file(ctx: Context, playlistId: String): File = File(ctx.cacheDir, "epg_$playlistId.tsv")

    fun candidateUrls(pl: Playlist, m3u: File): List<String> {
        val out = LinkedHashSet<String>()
        pl.epg?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { out.add(it) }
        val h = pl.url?.toHttpUrlOrNull()
        val user = h?.queryParameter("username")
        val pass = h?.queryParameter("password")
        if (h != null && !user.isNullOrEmpty() && !pass.isNullOrEmpty() && h.encodedPath.endsWith("get.php")) {
            out.add(
                h.newBuilder()
                    .encodedPath(h.encodedPath.removeSuffix("get.php") + "xmltv.php")
                    .query(null)
                    .addQueryParameter("username", user)
                    .addQueryParameter("password", pass)
                    .build()
                    .toString()
            )
        }
        if (m3u.exists()) runCatching { out.addAll(M3uParser.headerEpgUrls(m3u)) }
        return out.toList()
    }

    /** Downloads + parses the guide and writes the cache file. Returns number of programmes kept. */
    fun refresh(http: OkHttpClient, urls: List<String>, live: List<Channel>, tmp: File, out: File): Int {
        if (urls.isEmpty()) error("No guide link found - add an EPG link in the playlist")
        val wantIds = HashSet<String>()
        val wantNames = HashSet<String>()
        for (c in live) {
            c.tvgId?.let { wantIds.add(it.lowercase()) }
            c.tvgName?.let { epgNorm(it).takeIf { k -> k.isNotEmpty() }?.let(wantNames::add) }
            epgNorm(c.name).takeIf { it.isNotEmpty() }?.let(wantNames::add)
        }
        val slow = http.newBuilder().readTimeout(180, TimeUnit.SECONDS).build()
        var last: Exception? = null
        for (u in urls) {
            try {
                download(slow, u, tmp)
                val n = parse(tmp, wantIds, wantNames, out)
                tmp.delete()
                if (n > 0) return n
                last = IllegalStateException("Guide has no programmes for your channels")
            } catch (e: Exception) {
                last = e
            }
        }
        tmp.delete()
        throw last ?: IllegalStateException("Guide update failed")
    }

    private fun download(http: OkHttpClient, url: String, tmp: File) {
        val req = Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("Empty response")
            body.byteStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
        }
    }

    private fun open(f: File): InputStream {
        val bin = BufferedInputStream(FileInputStream(f), 64 * 1024)
        bin.mark(2)
        val b0 = bin.read()
        val b1 = bin.read()
        bin.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(bin, 64 * 1024) else bin
    }

    private fun parse(src: File, wantIds: Set<String>, wantNames: Set<String>, out: File): Int {
        val now = System.currentTimeMillis()
        val from = now - PAST_MS
        val to = now + FUTURE_MS
        val keep = HashSet<String>()
        val idOfName = HashMap<String, String>()
        val progs = HashMap<String, ArrayList<Programme>>()
        var total = 0

        open(src).use { input ->
            val p = Xml.newPullParser()
            p.setInput(input, null)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> {
                            val id = (p.getAttributeValue(null, "id") ?: "").lowercase()
                            val names = ArrayList<String>()
                            var d = p.next()
                            while (d != XmlPullParser.END_DOCUMENT && !(d == XmlPullParser.END_TAG && p.name == "channel")) {
                                if (d == XmlPullParser.START_TAG && p.name == "display-name") {
                                    names.add(epgNorm(p.nextText()))
                                }
                                d = p.next()
                            }
                            if (id.isNotEmpty() && (id in wantIds || names.any { it in wantNames })) {
                                keep.add(id)
                                for (n in names) if (n.isNotEmpty()) idOfName.putIfAbsent(n, id)
                            }
                        }
                        "programme" -> {
                            val id = (p.getAttributeValue(null, "channel") ?: "").lowercase()
                            val st = parseTime(p.getAttributeValue(null, "start"))
                            var sp = parseTime(p.getAttributeValue(null, "stop"))
                            var title = ""
                            var desc = ""
                            var d = p.next()
                            while (d != XmlPullParser.END_DOCUMENT && !(d == XmlPullParser.END_TAG && p.name == "programme")) {
                                if (d == XmlPullParser.START_TAG) {
                                    when (p.name) {
                                        "title" -> { val t = p.nextText().trim(); if (title.isEmpty()) title = t }
                                        "desc" -> { val t = p.nextText().trim(); if (desc.isEmpty()) desc = t }
                                    }
                                }
                                d = p.next()
                            }
                            if (sp <= st) sp = 0L // fixed up below from the next start time
                            if (id in keep && st > 0 && st < to && (sp == 0L || sp > from) &&
                                title.isNotEmpty() && total < MAX_PROGRAMMES
                            ) {
                                val dsc = if (st > now + DESC_HORIZON_MS) "" else desc.take(300)
                                progs.getOrPut(id) { ArrayList() }.add(Programme(st, sp, title, dsc))
                                total++
                            }
                        }
                    }
                }
                ev = p.next()
            }
        }

        // sort, and give programmes without an end time the start of the next one
        var count = 0
        for (list in progs.values) {
            list.sortBy { it.start }
            for (i in list.indices) {
                val pr = list[i]
                if (pr.stop == 0L) {
                    val end = if (i + 1 < list.size) list[i + 1].start else pr.start + 30 * 60_000L
                    list[i] = pr.copy(stop = end)
                }
            }
            count += list.size
        }
        if (count == 0) return 0

        val tmpOut = File(out.parentFile, out.name + ".part")
        tmpOut.bufferedWriter().use { w ->
            for ((n, id) in idOfName) w.write("N\t$n\t$id\n")
            for ((id, list) in progs) {
                for (pr in list) {
                    w.write("P\t$id\t${pr.start}\t${pr.stop}\t${clean(pr.title)}\t${clean(pr.desc)}\n")
                }
            }
        }
        tmpOut.copyTo(out, overwrite = true)
        tmpOut.delete()
        return count
    }

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun load(files: List<File>): EpgIndex {
        val limit = System.currentTimeMillis() - 3_600_000L
        val byId = HashMap<String, ArrayList<Programme>>()
        val names = HashMap<String, String>()
        for (f in files) {
            if (!f.exists()) continue
            runCatching {
                f.useLines { lines ->
                    for (line in lines) {
                        val t = line.split('\t', limit = 6)
                        when (t[0]) {
                            "N" -> if (t.size >= 3) names.putIfAbsent(t[1], t[2])
                            "P" -> if (t.size >= 6) {
                                val stop = t[3].toLong()
                                if (stop > limit) {
                                    byId.getOrPut(t[1]) { ArrayList() }
                                        .add(Programme(t[2].toLong(), stop, t[4], t[5]))
                                }
                            }
                        }
                    }
                }
            }
        }
        for (l in byId.values) l.sortBy { it.start }
        return EpgIndex(byId, names)
    }

    /** XMLTV: 20261025180000 +0800 */
    fun parseTime(s: String?): Long {
        if (s == null) return 0L
        val t = s.trim()
        if (t.length < 12) return 0L
        return try {
            fun n(a: Int, b: Int) = t.substring(a, b).toInt()
            val hasSec = t.length >= 14 && t[12].isDigit() && t[13].isDigit()
            val sec = if (hasSec) n(12, 14) else 0
            val rest = t.substring(if (hasSec) 14 else 12).trim()
            var off = 0L
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val m = rest.substring(1, 3).toInt() * 60 + rest.substring(3, 5).toInt()
                off = (if (rest[0] == '-') -m else m) * 60_000L
            }
            LocalDateTime.of(n(0, 4), n(4, 6), n(6, 8), n(8, 10), n(10, 12), sec)
                .toEpochSecond(ZoneOffset.UTC) * 1000L - off
        } catch (e: Exception) {
            0L
        }
    }
}
