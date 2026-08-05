package com.sgaikar1.edgedroid.download

import kotlin.time.Duration

/**
 * Downloader behaviour. Configured through `LlmSdk.Builder.download { ... }`.
 */
data class DownloadConfig(
    val maxRetries: Int = 3,
    val connectTimeout: Duration = Duration.parse("15s"),
    val readTimeout: Duration = Duration.parse("30s"),
    val chunkBufferBytes: Int = 256 * 1024,
) {
    class Builder {
        private var maxRetriesValue: Int = 3
        private var connectTimeoutValue: Duration = Duration.parse("15s")
        private var readTimeoutValue: Duration = Duration.parse("30s")
        private var chunkBufferKbValue: Int = 256

        fun maxRetries(retries: Int): Builder = apply { maxRetriesValue = retries }
        fun connectTimeout(duration: Duration): Builder = apply { connectTimeoutValue = duration }
        fun readTimeout(duration: Duration): Builder = apply { readTimeoutValue = duration }
        fun timeout(duration: Duration): Builder =
            apply {
                connectTimeoutValue = duration
                readTimeoutValue = duration
            }
        fun chunkBuffer(kb: Int): Builder = apply { chunkBufferKbValue = kb }

        fun build(): DownloadConfig = DownloadConfig(
            maxRetries = maxRetriesValue,
            connectTimeout = connectTimeoutValue,
            readTimeout = readTimeoutValue,
            chunkBufferBytes = chunkBufferKbValue * 1024,
        )
    }

    companion object {
        val DEFAULT = DownloadConfig()
    }
}
