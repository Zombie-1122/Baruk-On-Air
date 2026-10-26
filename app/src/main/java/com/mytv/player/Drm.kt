package com.mytv.player

import java.net.URLDecoder
import java.util.Base64

/** Helpers for playlists that carry Kodi-style DRM tags (#KODIPROP:inputstream.adaptive.*). */
object Drm {
    private val hexRe = Regex("^[0-9a-fA-F]+$")

    private fun hexToB64Url(h: String): String {
        val bytes = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * ClearKey license_key comes as "kid:key" (hex, several pairs separated by comma)
     * or already as a JSON {"keys":[...]} document. Returns the JSON the Android
     * ClearKey plugin expects, or null when the value is a license-server URL / unusable.
     */
    fun clearKeyJson(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.startsWith("{")) return s
        val keys = ArrayList<String>()
        for (pair in s.split(',')) {
            val kv = pair.trim().split(':')
            if (kv.size != 2) continue
            val kid = kv[0].trim()
            val k = kv[1].trim()
            if (kid.length % 2 != 0 || k.length % 2 != 0) continue
            if (!hexRe.matches(kid) || !hexRe.matches(k)) continue
            keys.add("""{"kty":"oct","k":"${hexToB64Url(k)}","kid":"${hexToB64Url(kid)}"}""")
        }
        if (keys.isEmpty()) return null
        return """{"keys":[${keys.joinToString(",")}],"type":"temporary"}"""
    }

    /** "User-Agent=x&Referer=y" (values may be URL-encoded) -> map. */
    fun parseHeaders(s: String?): Map<String, String> {
        if (s.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in s.split('&')) {
            val k = part.substringBefore('=').trim()
            if (k.isEmpty() || !part.contains('=')) continue
            val v = part.substringAfter('=')
            out[k] = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
        return out
    }

    fun isDash(manifestType: String?, url: String): Boolean {
        val m = manifestType?.lowercase()
        return m == "mpd" || m == "dash" || url.substringBefore('?').lowercase().endsWith(".mpd")
    }

    fun isHls(manifestType: String?, url: String): Boolean {
        val m = manifestType?.lowercase()
        return m == "hls" || url.substringBefore('?').lowercase().endsWith(".m3u8")
    }
}

/** Natural, case-insensitive compare: "TV2" < "TV10". */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i
            while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0')
            val nb = b.substring(j, ej).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = ei
            j = ej
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}
