package com.sgaikar1.edgedroid.download

import kotlin.time.Duration

/**
 * Downloader behaviour. Configured through `EdgeDroid.Builder.download { ... }`.
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
    val foregroundServiceEnabled: Boolean = true,
    val notificationChannelId: String = "edgedroid_downloads",
    val notificationChannelName: String = "Model downloads",
    val notificationTitle: String = "Downloading model",
) {
    class Builder {
        private var maxRetriesValue: Int = 3
        private var connectTimeoutValue: Duration = Duration.parse("15s")
        private var readTimeoutValue: Duration = Duration.parse("30s")
        private var chunkBufferKbValue: Int = 256
        private var headersValue: Map<String, String> = emptyMap()
        private var fgEnabledValue: Boolean = true
        private var channelIdValue: String = "edgedroid_downloads"
        private var channelNameValue: String = "Model downloads"
        private var titleValue: String = "Downloading model"

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

        /** Run active downloads in a foreground service so they survive backgrounding. */
        fun foregroundService(enabled: Boolean): Builder = apply { fgEnabledValue = enabled }

        /** Override the notification channel + title used by the foreground service. */
        fun notification(channelId: String = channelIdValue, channelName: String = channelNameValue, title: String = titleValue): Builder =
            apply {
                channelIdValue = channelId
                channelNameValue = channelName
                titleValue = title
            }

        fun build(): DownloadConfig = DownloadConfig(
            maxRetries = maxRetriesValue,
            connectTimeout = connectTimeoutValue,
            readTimeout = readTimeoutValue,
            chunkBufferBytes = chunkBufferKbValue * 1024,
            headers = headersValue,
            foregroundServiceEnabled = fgEnabledValue,
            notificationChannelId = channelIdValue,
            notificationChannelName = channelNameValue,
            notificationTitle = titleValue,
        )
    }

    companion object {
        val DEFAULT = DownloadConfig()
    }
}
