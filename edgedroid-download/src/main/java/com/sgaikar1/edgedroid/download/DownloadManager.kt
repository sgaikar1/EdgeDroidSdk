package com.sgaikar1.edgedroid.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Process-wide handle so [DownloadService] (same process) can reach the active manager after
 * the app recreates its SDK. Last built manager wins (single-SDK apps).
 */
internal object DownloadManagerHolder {
    @Volatile var manager: DownloadManager? = null
}

/**
 * [Downloader] implementation over OkHttp. Downloads are manager-scoped (not caller-scoped):
 * `download(model)` returns a shared state flow, while `pause/resume/cancel` control the
 * underlying task from anywhere. Partial files survive pause/resume via HTTP Range requests.
 *
 * When built with a [context], active downloads are hosted by a foreground service
 * ([DownloadService]) so they survive backgrounding / process reclamation. If the service
 * cannot be started (e.g. Android 12+ background-start restriction) the download simply runs
 * in-process. Calling `download()` on an already-downloaded model completes immediately with no
 * network activity.
 */
class DownloadManager(
    private val storage: ModelStorage,
    internal val config: DownloadConfig = DownloadConfig.DEFAULT,
    private val log: LogProvider = LogProvider.NO_OP,
    private val preflight: (Model) -> ModelDownloadState.Failed? = { null },
    private val context: Context? = null,
) : Downloader {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeout.inWholeSeconds, TimeUnit.SECONDS)
        .readTimeout(config.readTimeout.inWholeSeconds, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<String, DownloadTask>()

    private val _allStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())

    /** All known per-model download states; the service mirrors this into the notification. */
    internal val allStates: StateFlow<Map<String, ModelDownloadState>> = _allStates.asStateFlow()

    init {
        DownloadManagerHolder.manager = this
    }

    override fun download(model: Model): Flow<ModelDownloadState> {
        // Already on device → complete immediately, no network.
        if (storage.isDownloaded(model)) {
            val path = storage.modelPath(model).absolutePath
            log.log(LogProvider.Level.DEBUG, TAG, "Model '${model.id}' already on device at $path")
            return flow { emit(ModelDownloadState.Completed(path)) }
        }
        startForegroundService(model)
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

    /** The foreground service calls this after process recreation to resume a download. */
    internal fun startFromService(model: Model) {
        download(model)
    }

    private fun startForegroundService(model: Model) {
        val ctx = context ?: return
        if (!config.foregroundServiceEnabled) return
        try {
            val intent = Intent(ctx, DownloadService::class.java)
                .putExtra(DownloadService.EXTRA_MODEL, Json.encodeToString(Model.serializer(), model))
            ContextCompat.startForegroundService(ctx, intent)
        } catch (t: Throwable) {
            // Android 12+ foreground-service-start restriction → fall back to in-process.
            log.log(LogProvider.Level.WARN, TAG, "Foreground service not started (${t.message}); running in-process")
        }
    }

    private inner class DownloadTask(val model: Model) {
        val state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
        @Volatile var isPaused: Boolean = false

        private var job: Job? = null

        private fun setState(s: ModelDownloadState) {
            state.value = s
            _allStates.value = _allStates.value + (model.id to s)
        }

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
            setState(ModelDownloadState.Cancelled)
        }

        private suspend fun downloadInternal() {
            val url = model.downloadUrl
            if (url.isNullOrBlank()) {
                setState(ModelDownloadState.Failed("config", "model has no downloadUrl"))
                return
            }

            preflight(model)?.let {
                setState(it)
                log.log(LogProvider.Level.WARN, TAG, "Download refused by preflight: ${it.message}")
                return
            }

            val partFile = File(storage.downloadsDir, "${model.id}.part")
            val finalFile = storage.modelPath(model)
            partFile.parentFile?.mkdirs()
            finalFile.parentFile?.mkdirs()
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
                            setState(ModelDownloadState.Paused(offset))
                            return
                        }
                        if (retries-- > 0) {
                            log.log(LogProvider.Level.WARN, TAG, "Retry (${config.maxRetries - retries}): ${t.message}")
                            continue
                        }
                        setState(ModelDownloadState.Failed("network", t.message ?: "network error"))
                        return
                    }

                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            setState(
                                ModelDownloadState.Failed(
                                    "http", "HTTP ${resp.code} while downloading '${model.id}'",
                                ),
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
                            setState(ModelDownloadState.Failed("io", "empty response body"))
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
                                        setState(ModelDownloadState.Paused(partFile.length()))
                                        return
                                    }
                                    val n = try {
                                        input.read(buf)
                                    } catch (t: Throwable) {
                                        if (isPaused) {
                                            setState(ModelDownloadState.Paused(partFile.length()))
                                        } else {
                                            setState(ModelDownloadState.Failed("io", t.message ?: "read error"))
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
                                            setState(ModelDownloadState.Downloading(received, totalBytes, progress))
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
                    setState(ModelDownloadState.Paused(partFile.length()))
                    return
                }

                if (!verifySha256(partFile, model.sha256)) {
                    setState(ModelDownloadState.Failed("verification", "sha256 mismatch for '${model.id}'"))
                    partFile.delete()
                    return
                }

                finalFile.parentFile?.mkdirs()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                storage.record(model, finalFile.absolutePath)
                setState(ModelDownloadState.Completed(finalFile.absolutePath))
                log.log(LogProvider.Level.INFO, TAG, "Download completed: ${finalFile.absolutePath}")
            } catch (e: CancellationException) {
                if (isPaused) {
                    setState(ModelDownloadState.Paused(partFile.length()))
                }
                throw e
            } catch (t: Throwable) {
                setState(ModelDownloadState.Failed("unknown", t.message ?: "download failed"))
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
