package com.sgaikar1.edgedroid.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadConfigTest {

    @Test
    fun `headers map is carried into build`() {
        val config = DownloadConfig.Builder()
            .headers(mapOf("Authorization" to "Bearer token"))
            .build()
        assertEquals(mapOf("Authorization" to "Bearer token"), config.headers)
    }

    @Test
    fun `header adds and overrides a single entry`() {
        val config = DownloadConfig.Builder()
            .header("Authorization", "Bearer first")
            .header("X-Custom", "a")
            .header("Authorization", "Bearer second")
            .build()
        assertEquals(
            mapOf("Authorization" to "Bearer second", "X-Custom" to "a"),
            config.headers,
        )
    }

    @Test
    fun `headers map replaces previously set entries`() {
        val config = DownloadConfig.Builder()
            .header("Authorization", "Bearer token")
            .headers(mapOf("X-Other" to "b"))
            .build()
        assertEquals(mapOf("X-Other" to "b"), config.headers)
    }

    @Test
    fun `default config has no headers`() {
        assertEquals(emptyMap<String, String>(), DownloadConfig.DEFAULT.headers)
    }
}
