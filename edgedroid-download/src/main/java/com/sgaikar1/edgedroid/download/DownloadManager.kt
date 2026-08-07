package com.sgaikar1.edgedroid.download

import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.core.Downloader
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelDownloadState
import com.sgaikar1.edgedroid.core.ModelStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * [Downloader] implementation over OkHttp. Downloads are manager-scoped (not caller-scoped):
 * `download(model)` returns a shared state flow, while `pause/resume/cancel` control the
 * underlying task from anywhere. Partial files survive pause/resume via HTTP Range requests.
 */
class DownloadManager(
    private val storage: ModelStorage,
    private val config: DownloadConfig = DownloadConfig.DEFAULT,
    private val log: LogProvider = LogProvider.NO_OP,
    private val preflight: (Model) -> ModelDownloadState.Failed? = { null },
) : Downloader {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeout.inWholeSeconds, TimeUnit.SECONDS)
        .readTimeout(config.readTimeout.inWholeSeconds, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<String, DownloadTask>()

    override fun download(model: Model): Flow<ModelDownloadState> {
        val task = tasks.getOrPut(model.id) { DownloadTask(model) }
        task.ensureStarted()
        return task.state.asStateFlow()
    }

    override suspend fun pause(modelId: String) {
        tasks[modelId]?.pause()
    }

    override suspend fun resume(modelId: String) {
        tasks[modelId]?.let { task ->
            if (task.isPaused) task.ensureStarted()
        }
    }

    override suspend fun cancel(modelId: String) {
        tasks.remove(modelId)?.cancel()
    }

    override suspend fun delete(modelId: String) {
        tasks.remove(modelId)?.cancel()
        storage.resolve(modelId)?.let { storage.delete(it) }
    }

    override fun stateFor(modelId: String): ModelDownloadState? = tasks[modelId]?.state?.value

    private inner class DownloadTask(val model: Model) {
        val state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
        @Volatile var isPaused: Boolean = false

        private var job: Job? = null

        fun ensureStarted() {
            synchronized(this) {
                if (state.value is ModelDownloadState.Completed) return
                if (job?.isActive == true) return
                isPaused = false
                job = scope.launch { downloadInternal() }
            }
        }

        fun pause() {
            isPaused = true
            job?.cancel()
        }

        fun cancel() {
            job?.cancel(CancellationException("download cancelled"))
            state.value = ModelDownloadState.Cancelled
        }

        private suspend fun downloadInternal() {
            val url = model.downloadUrl
            if (url.isNullOrBlank()) {
                state.value = ModelDownloadState.Failed("config", "model has no downloadUrl")
                return
            }

            preflight(model)?.let {
                state.value = it
                log.log(LogProvider.Level.WARN, TAG, "Download refused by preflight: ${it.message}")
                return
            }

            val partFile = File(storage.downloadsDir, "${model.id}.part")
            val finalFile = storage.modelPath(model)
            var retries = config.maxRetries

            try {
                var offset = partFile.length()
                var total: Long? = null
                var done = false

                while (!done) {
                    val request = Request.Builder()
                        .url(url)
                        .apply { config.headers.forEach { (k, v) -> header(k, v) } }
                        .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                        .build()

                    val response = try {
                        client.newCall(request).execute()
                    } catch (t: Throwable) {
                        if (isPaused) {
                            state.value = ModelDownloadState.Paused(offset)
                            return
                        }
                        if (retries-- > 0) {
                            log.log(LogProvider.Level.WARN, TAG, "Retry (${config.maxRetries - retries}): ${t.message}")
                            continue
                        }
                        state.value = ModelDownloadState.Failed("network", t.message ?: "network error", )
                        return
                    }

                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            state.value = ModelDownloadState.Failed(
                                "http", "HTTP ${resp.code} while downloading '${model.id}'",
                            )
                            return
                        }
                        if (resp.code == 200) {
                            offset = 0
                        }
                        if (total == null) {
                            total = resp.body?.contentLength()?.let { offset + it }
                        }
                        val body = resp.body ?: run {
                            state.value = ModelDownloadState.Failed("io", "empty response body")
                            return
                        }

                        val totalBytes = total
                        FileOutputStream(partFile, offset > 0).use { out ->
                            val buf = ByteArray(config.chunkBufferBytes)
                            body.byteStream().use { input ->
                                var lastEmit = 0L
                                var reading = true
                                while (reading) {
                                    if (isPaused) {
                                        state.value = ModelDownloadState.Paused(partFile.length())
                                        return
                                    }
                                    val n = try {
                                        input.read(buf)
                                    } catch (t: Throwable) {
                                        if (isPaused) {
                                            state.value = ModelDownloadState.Paused(partFile.length())
                                        } else {
                                            state.value = ModelDownloadState.Failed("io", t.message ?: "read error")
                                        }
                                        return
                                    }
                                    if (n >= 0) {
                                        out.write(buf, 0, n)
                                        val received = partFile.length()
                                        if (received - lastEmit >= 512 * 1024 || totalBytes != null) {
                                            lastEmit = received
                                            val progress = if (totalBytes != null && totalBytes > 0) {
                                                received.toFloat() / totalBytes
                                            } else {
                                                0f
                                            }
                                            state.value = ModelDownloadState.Downloading(received, totalBytes, progress)
                                        }
                                    } else {
                                        reading = false
                                    }
                                }
                            }
                        }
                        done = true
                    }
                }

                if (isPaused) {
                    state.value = ModelDownloadState.Paused(partFile.length())
                    return
                }

                if (!verifySha256(partFile, model.sha256)) {
                    state.value = ModelDownloadState.Failed("verification", "sha256 mismatch for '${model.id}'")
                    partFile.delete()
                    return
                }

                finalFile.parentFile?.mkdirs()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                storage.record(model, finalFile.absolutePath)
                state.value = ModelDownloadState.Completed(finalFile.absolutePath)
                log.log(LogProvider.Level.INFO, TAG, "Download completed: ${finalFile.absolutePath}")
            } catch (e: CancellationException) {
                if (isPaused) {
                    state.value = ModelDownloadState.Paused(partFile.length())
                }
                throw e
            } catch (t: Throwable) {
                state.value = ModelDownloadState.Failed("unknown", t.message ?: "download failed")
            }
        }

        private fun verifySha256(file: File, expected: String?): Boolean {
            if (expected.isNullOrBlank()) return true
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                actual.equals(expected, ignoreCase = true)
            } catch (t: Throwable) {
                false
            }
        }
    }

    companion object {
        private const val TAG = "EdgeDroid.Download"
    }
}
