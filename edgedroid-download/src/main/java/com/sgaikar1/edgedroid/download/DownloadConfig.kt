package com.sgaikar1.edgedroid.download

import kotlin.time.Duration

/**
 * Downloader behaviour. Configured through `LlmSdk.Builder.download { ... }`.
 *
 * [headers] are attached to every model download request (initial and resume). They are
 * never logged or persisted — set them in code, e.g. for gated models:
 * `.download { header("Authorization", "Bearer <token>") }`.
 */
data class DownloadConfig(
    val maxRetries: Int = 3,
    val connectTimeout: Duration = Duration.parse("15s"),
    val readTimeout: Duration = Duration.parse("30s"),
    val chunkBufferBytes: Int = 256 * 1024,
    val headers: Map<String, String> = emptyMap(),
) {
    class Builder {
        private var maxRetriesValue: Int = 3
        private var connectTimeoutValue: Duration = Duration.parse("15s")
        private var readTimeoutValue: Duration = Duration.parse("30s")
        private var chunkBufferKbValue: Int = 256
        private var headersValue: Map<String, String> = emptyMap()

        fun maxRetries(retries: Int): Builder = apply { maxRetriesValue = retries }
        fun connectTimeout(duration: Duration): Builder = apply { connectTimeoutValue = duration }
        fun readTimeout(duration: Duration): Builder = apply { readTimeoutValue = duration }
        fun timeout(duration: Duration): Builder =
            apply {
                connectTimeoutValue = duration
                readTimeoutValue = duration
            }
        fun chunkBuffer(kb: Int): Builder = apply { chunkBufferKbValue = kb }

        /** Replace the whole set of request headers. */
        fun headers(headers: Map<String, String>): Builder = apply { headersValue = headers }

        /** Add or override a single request header (e.g. `Authorization`). */
        fun header(key: String, value: String): Builder =
            apply { headersValue = headersValue + (key to value) }

        fun build(): DownloadConfig = DownloadConfig(
            maxRetries = maxRetriesValue,
            connectTimeout = connectTimeoutValue,
            readTimeout = readTimeoutValue,
            chunkBufferBytes = chunkBufferKbValue * 1024,
            headers = headersValue,
        )
    }

    companion object {
        val DEFAULT = DownloadConfig()
    }
}
