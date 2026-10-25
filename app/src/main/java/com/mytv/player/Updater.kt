package com.mytv.player

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class UpdateInfo(
    val build: Int,
    val tag: String,
    val apkUrl: String,
    val notes: String,
)

/** Reads the newest GitHub release of "owner/repo" (tag looks like build-12). */
fun fetchLatestRelease(http: OkHttpClient, repo: String): UpdateInfo {
    val req = Request.Builder()
        .url("https://api.github.com/repos/$repo/releases/latest")
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "BarukOnAir")
        .build()
    return http.newCall(req).execute().use { r ->
        if (r.code == 404) error("Repo not found or has no releases (is it Public?)")
        if (!r.isSuccessful) error("HTTP ${r.code}")
        val text = r.body?.string() ?: error("Empty response")
        val o = JSONObject(text)
        val tag = o.optString("tag_name", "")
        val build = tag.filter { it.isDigit() }.toIntOrNull() ?: 0
        var apk = ""
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    apk = a.optString("browser_download_url")
                    break
                }
            }
        }
        if (apk.isEmpty()) apk = o.optString("html_url")
        val notes = o.optString("body", "").trim().ifEmpty { o.optString("name", "") }
        UpdateInfo(build, tag, apk, notes)
    }
}
