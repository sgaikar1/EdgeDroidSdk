package com.sgaikar1.edgedroid.download

import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelDownloadState
import com.sgaikar1.edgedroid.core.ModelStorage
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadManagerHeadersTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: TempStorage

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        storage = TempStorage()
    }

    @After
    fun tearDown() {
        server.shutdown()
        storage.dir.deleteRecursively()
    }

    private fun model(url: String): Model = Model(
        id = "private-model",
        name = "private-model",
        format = ModelFormat.GGUF,
        downloadUrl = url,
        sizeBytes = 64L,
    )

    private fun manager(headers: Map<String, String>): DownloadManager =
        DownloadManager(storage = storage.modelStorage(), config = DownloadConfig(headers = headers))

    /** Collect until a terminal state; fail fast (no infinite hang) if the download errors. */
    private suspend fun kotlinx.coroutines.flow.Flow<ModelDownloadState>.awaitTerminal() {
        first {
            it is ModelDownloadState.Completed ||
                it is ModelDownloadState.Failed ||
                it is ModelDownloadState.Cancelled
        }
    }

    @Test
    fun `auth header is attached to the download request`() = runBlocking {
        server.enqueue(MockResponse().setBody("0123456789"))

        manager(mapOf("Authorization" to "Bearer secret-token"))
            .download(model(server.url("/private/model.gguf").toString()))
            .awaitTerminal()

        val request = server.takeRequest()
        assertEquals("/private/model.gguf", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
    }

    @Test
    fun `range header is present on a resumed download alongside auth`() = runBlocking {
        // Simulate a partially downloaded file so the manager resumes at byte 10.
        storage.partFile().apply {
            parentFile?.mkdirs()
            writeText("0123456789") // 10 bytes
        }

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 10-19/20")
                .setBody("0123456789"),
        )

        manager(mapOf("X-Api-Key" to "abc"))
            .download(model(server.url("/private/model.gguf").toString()))
            .awaitTerminal()

        val request = server.takeRequest()
        assertEquals("bytes=10-", request.getHeader("Range"))
        assertEquals("abc", request.getHeader("X-Api-Key"))
        assertTrue(storage.modelFile().exists())
    }

    private class TempStorage {
        val dir: File = java.nio.file.Files.createTempDirectory("edgedroid-dl").toFile()

        fun partFile(): File = File(dir, "downloads/private-model.part")

        fun modelFile(): File = File(dir, "models/private-model.gguf")

        fun modelStorage(): ModelStorage {
            File(dir, "downloads").mkdirs()
            File(dir, "models").mkdirs()
            return object : ModelStorage {
                override val rootDir: File = dir
                override val modelsDir: File = File(dir, "models")
                override val downloadsDir: File = File(dir, "downloads")
                override val cacheDir: File = File(dir, "cache")
                override val tempDir: File = File(dir, "temp")
                override fun modelPath(model: Model): File = File(modelsDir, "${model.id}.gguf")
                override fun isDownloaded(model: Model): Boolean = modelPath(model).exists()
                override fun record(model: Model, localPath: String) = Unit
                override fun resolve(id: String): Model? = null
                override fun allModels(): List<Model> = emptyList()
                override fun delete(model: Model): Boolean = modelPath(model).delete()
            }
        }
    }
}
