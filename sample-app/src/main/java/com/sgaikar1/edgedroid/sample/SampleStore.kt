package com.sgaikar1.edgedroid.sample

import android.content.Context
import com.sgaikar1.edgedroid.api.EdgeDroid
import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.DeviceCapabilities
import com.sgaikar1.edgedroid.core.GpuConfig
import com.sgaikar1.edgedroid.core.LlmEngineState
import com.sgaikar1.edgedroid.server.EdgeDroidOpenAiServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Snapshot of the local OpenAI-compatible server shown in the UI. */
data class ServerUiState(
    val running: Boolean = false,
    val url: String? = null,
    val error: String? = null,
)

/**
 * Owns the single active [EdgeDroid] and the [SampleConfig] that produced it. Applying a new
 * config rebuilds the SDK (unloading the previous one) — the chat/embedding UI always talks
 * to [sdk] and observes the current engine state via [sdkState]. The config is persisted so a
 * process restart restores the last selection.
 */
class SampleStore(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("edgedroid_sample", Context.MODE_PRIVATE)

    /** Hardware snapshot used to pre-filter models and pick device-aware defaults. */
    val capabilities: DeviceCapabilities = EdgeDroid.deviceCapabilities(appContext)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<SampleConfig> = _config.asStateFlow()

    private val _sdkState = MutableStateFlow<LlmEngineState>(LlmEngineState.Idle)
    val sdkState: StateFlow<LlmEngineState> = _sdkState.asStateFlow()

    private val _downloadedIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds.asStateFlow()

    private val _serverState = MutableStateFlow(ServerUiState())
    val serverState: StateFlow<ServerUiState> = _serverState.asStateFlow()

    @Volatile
    private var server: EdgeDroidOpenAiServer? = null

    @Volatile
    var sdk: EdgeDroid = SdkFactory.build(appContext, _config.value)
        private set

    private var stateJob: Job? = null

    init {
        subscribeState()
        refreshDownloaded()
    }

    /**
     * Rebuild the SDK from [newConfig]. Runs the (potentially network-fetching) build off the
     * main thread; returns the failure (e.g. mmproj/tokenizer fetch error) or null on success.
     * The previous SDK is unloaded on the background scope.
     */
    suspend fun apply(newConfig: SampleConfig): Throwable? =
        withContext(Dispatchers.Default) {
            runCatching {
                val old = sdk
                val built = SdkFactory.build(appContext, newConfig)
                _config.value = newConfig
                sdk = built
                sdk.systemPrompt = newConfig.systemPrompt
                saveConfig(newConfig)
                subscribeState()
                refreshDownloaded()
                scope.launch { runCatching { old.unload() } }
                syncServer()
            }.exceptionOrNull()
        }

    // ---- local OpenAI-compatible server ----

    /** App lifecycle hook: restart the server when the app returns to the foreground. */
    fun onAppForeground() {
        scope.launch { syncServer() }
    }

    /** App lifecycle hook: stop the server when the app leaves the foreground. */
    fun onAppBackground() {
        scope.launch { syncServer(stopOnly = true) }
    }

    /**
     * Reflect [config.serverEnabled] in a running [EdgeDroidOpenAiServer] bound to the
     * current [sdk]. Called after config changes and on foreground/background transitions.
     */
    private fun syncServer(stopOnly: Boolean = false) {
        val cfg = _config.value
        val old = server
        server = null
        if (old != null) {
            runCatching { old.stop() }
        }
        if (stopOnly || !cfg.serverEnabled) {
            _serverState.value = ServerUiState()
            return
        }
        val bound = EdgeDroidOpenAiServer(
            sdk = sdk,
            port = cfg.serverPort,
            defaultModelId = cfg.model.id,
        )
        try {
            bound.start()
            server = bound
            _serverState.value = ServerUiState(running = true, url = bound.baseUrl)
        } catch (t: Throwable) {
            runCatching { bound.stop() }
            _serverState.value = ServerUiState(error = t.message ?: "Failed to start local server")
        }
    }

    /** Recompute which configured models are actually on device (file exists). */
    fun refreshDownloaded() {
        _downloadedIds.value = sdk.models.available()
            .filter { model -> model.localPath?.let { java.io.File(it).exists() } == true }
            .map { it.id }
            .toSet()
    }

    /** Delete a downloaded model's files (and any mmproj/tokenizer sidecar) from disk. */
    suspend fun deleteModel(id: String) {
        withContext(Dispatchers.Default) {
            runCatching { sdk.models.delete(id) }
            refreshDownloaded()
        }
    }

    private fun subscribeState() {
        stateJob?.cancel()
        stateJob = scope.launch { sdk.state.collect { _sdkState.value = it } }
    }

    // ---- persistence (org.json) ----

    private fun loadConfig(): SampleConfig {
        val raw = prefs.getString(KEY_CONFIG, null)
            ?: return SampleConfig.recommended(capabilities)
        return runCatching {
            val o = JSONObject(raw)
            val custom = o.optJSONObject("customModel")?.let {
                HfModelSelection(
                    repoId = it.getString("repoId"),
                    fileName = it.getString("fileName"),
                    format = ModelFormat.valueOf(it.getString("format")),
                    sizeBytes = if (it.isNull("sizeBytes")) null else it.getLong("sizeBytes"),
                )
            }
            val gpu = when (o.optString("gpu", "AUTO")) {
                "CPU" -> GpuConfig.Cpu
                "ALL" -> GpuConfig.All
                else -> GpuConfig.Auto
            }
            SampleConfig(
                runtime = SampleRuntime.valueOf(o.optString("runtime", SampleRuntime.LLAMA.name)),
                modelId = o.optString("modelId", "smollm2-135m-instruct"),
                customModel = custom,
                threads = o.optInt("threads", 4),
                contextSize = o.optInt("contextSize", 2048),
                gpu = gpu,
                executionProvider = if (o.isNull("executionProvider")) null else o.optString("executionProvider", "NNAPI"),
                maxRetries = o.optInt("maxRetries", 3),
                downloadTimeoutSeconds = o.optLong("downloadTimeoutSeconds", 60),
                temperature = o.optDouble("temperature", 0.8).toFloat(),
                topP = o.optDouble("topP", 0.95).toFloat(),
                topK = o.optInt("topK", 40),
                systemPrompt = o.optString("systemPrompt", ""),
                serverEnabled = o.optBoolean("serverEnabled", false),
                serverPort = o.optInt("serverPort", 8080).coerceIn(1, 65535),
            )
        }.getOrNull() ?: SampleConfig.recommended(capabilities)
    }

    private fun saveConfig(c: SampleConfig) {
        val o = JSONObject()
        o.put("runtime", c.runtime.name)
        o.put("modelId", c.modelId)
        c.customModel?.let {
            o.put(
                "customModel",
                JSONObject()
                    .put("repoId", it.repoId)
                    .put("fileName", it.fileName)
                    .put("format", it.format.name)
                    .put("sizeBytes", it.sizeBytes),
            )
        }
        o.put("threads", c.threads)
        o.put("contextSize", c.contextSize)
        o.put("gpu", when (c.gpu) {
            is GpuConfig.Cpu -> "CPU"
            is GpuConfig.All -> "ALL"
            else -> "AUTO"
        })
        if (c.executionProvider == null) o.put("executionProvider", JSONObject.NULL) else o.put("executionProvider", c.executionProvider)
        o.put("maxRetries", c.maxRetries)
        o.put("downloadTimeoutSeconds", c.downloadTimeoutSeconds)
        o.put("temperature", c.temperature.toDouble())
        o.put("topP", c.topP.toDouble())
        o.put("topK", c.topK)
        o.put("systemPrompt", c.systemPrompt)
        o.put("serverEnabled", c.serverEnabled)
        o.put("serverPort", c.serverPort)
        prefs.edit().putString(KEY_CONFIG, o.toString()).apply()
    }

    companion object {
        private const val KEY_CONFIG = "config"
    }
}
