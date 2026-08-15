package com.sgaikar1.edgedroid.sample

import com.sgaikar1.edgedroid.common.ModelFormat
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

    /** Small pool so per-repo tree fetches (needed for real file sizes) run in parallel. */
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(4)

    fun searchModels(
        query: String,
        filter: String,
        maxBytes: Long,
        minParams: Float = 0f,
        maxParams: Float = 0f,
        limit: Int = 50,
    ): List<HfModelSummary> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$API/models?search=$q&filter=$filter&sort=downloads&direction=-1&limit=$limit"
        val arr = JSONArray(get(url))
        val fmt = if (filter == "onnx") ModelFormat.ONNX else ModelFormat.GGUF
        val paramsFiltered = minParams > 0f || maxParams > 0f
        val hits = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    Triple(
                        o.optString("id", ""),
                        o.optLong("downloads", 0L),
                        o.optLong("likes", 0L),
                    ),
                )
            }
        }
        // The search API never returns file sizes, so fetch each repo's tree in parallel
        // (the `tree` endpoint is the only one that includes real file sizes).
        val futures = hits.map { (id, _, _) -> pool.submit<List<HfFile>> { listFiles(id) } }
        return buildList {
            for (i in futures.indices) {
                val (id, downloads, likes) = hits[i]
                val params = parseParamsBillion(id)
                val paramsOk = !paramsFiltered || (params != null && params >= minParams && params <= maxParams)
                if (!paramsOk) continue
                val files = futures[i].get()
                val eligible = files.filter {
                    isEligibleFile(it.name, fmt, maxBytes) && (maxBytes <= 0 || it.size in 1L..maxBytes)
                }
                if (eligible.isNotEmpty()) {
                    add(HfModelSummary(id, downloads, likes, eligible))
                }
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

    /** Stream a (possibly large) file to disk without buffering it in memory. */
    fun downloadTo(url: String, file: java.io.File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200) {
                throw RuntimeException("HTTP ${conn.responseCode} downloading $url")
            }
            file.parentFile?.mkdirs()
            file.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
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
