package com.sgaikar1.edgedroid.sample

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal HuggingFace Hub client (sample only — no SDK involvement). Uses plain
 * HttpURLConnection + org.json so no extra dependencies are needed.
 */
object HfHubClient {

    private const val API = "https://huggingface.co/api"

    fun searchModels(query: String, filter: String, limit: Int = 50): List<HfModelSummary> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$API/models?search=$q&filter=$filter&sort=downloads&direction=-1&limit=$limit"
        val body = get(url)
        val arr = JSONArray(body)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    HfModelSummary(
                        id = o.optString("id", ""),
                        downloads = o.optLong("downloads", 0L),
                        likes = o.optLong("likes", 0L),
                    ),
                )
            }
        }
    }

    fun listFiles(repo: String): List<HfFile> {
        val url = "$API/models/$repo/tree/main?recursive=true"
        val body = get(url)
        val arr = JSONArray(body)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type", "") == "file") {
                    add(HfFile(name = o.optString("path", ""), size = o.optLong("size", 0L)))
                }
            }
        }
    }

    fun resolveUrl(repo: String, file: String): String = "https://huggingface.co/$repo/resolve/main/$file"

    fun fetchBytes(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) {
                throw RuntimeException("HTTP ${conn.responseCode} fetching $url")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "EdgeDroidSample/0.7")
        try {
            if (conn.responseCode != 200) {
                throw RuntimeException("HTTP ${conn.responseCode} GET $url")
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
