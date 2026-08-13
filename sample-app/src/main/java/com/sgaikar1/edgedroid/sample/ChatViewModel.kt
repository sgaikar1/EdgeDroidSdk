package com.sgaikar1.edgedroid.sample

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.core.LlmEngineState
import com.sgaikar1.edgedroid.core.ModelDownloadState
import com.sgaikar1.edgedroid.core.PromptProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val role: String,
    val text: String,
    val reasoning: String? = null,
)

class ChatViewModel(
    private val app: EdgeDroidApp,
) : ViewModel() {

    private val store = app.sampleStore
    private val sdk get() = store.sdk

    val config: StateFlow<SampleConfig> = store.config

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    private val _reasoningText = MutableStateFlow<String?>(null)
    val reasoningText: StateFlow<String?> = _reasoningText.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _compatibility = MutableStateFlow<String?>(null)
    val compatibility: StateFlow<String?> = _compatibility.asStateFlow()

    private val _embeddingResult = MutableStateFlow<String?>(null)
    val embeddingResult: StateFlow<String?> = _embeddingResult.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    private val _attachedImage = MutableStateFlow<ByteArray?>(null)
    val attachedImage: StateFlow<ByteArray?> = _attachedImage.asStateFlow()

    fun attachImage(bytes: ByteArray) {
        _attachedImage.value = bytes
    }

    fun clearImage() {
        _attachedImage.value = null
    }

    val engineState: StateFlow<LlmEngineState> = store.sdkState

    val downloadedIds: StateFlow<Set<String>> = store.downloadedIds

    private var generationJob: Job? = null

    fun isDownloaded(modelId: String): Boolean = modelId in store.downloadedIds.value

    fun applyConfig(newConfig: SampleConfig) {
        viewModelScope.launch {
            val error = store.apply(newConfig)
            if (error != null) {
                _error.value = "Failed to apply settings: ${error.message}"
                return@launch
            }
            _messages.value = emptyList()
            _streamingText.value = null
            _reasoningText.value = null
            _embeddingResult.value = null
            _compatibility.value = null
            _error.value = null
        }
    }

    // ---- chat ----

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty() || generationJob?.isActive == true) return
        val cfg = config.value

        _messages.update { it + ChatMessage("user", text) }
        _streamingText.value = ""
        _reasoningText.value = null
        val raw = StringBuilder()
        val answer = StringBuilder()
        val reasoning = StringBuilder()

        generationJob = viewModelScope.launch {
            val image = _attachedImage.value
            try {
                sdk.stream(
                    text,
                    images = if (image != null) {
                        listOf(PromptProcessor.PromptAttachment(image, "image/jpeg"))
                    } else {
                        emptyList()
                    },
                    options = GenerationOptions(
                        temperature = cfg.temperature,
                        topK = cfg.topK,
                        topP = cfg.topP,
                    ),
                ) { token: Token ->
                    raw.append(token.text)
                    val parts = ReasoningParser.split(raw.toString())
                    parts.reasoning?.let { r ->
                        reasoning.setLength(0)
                        reasoning.append(r)
                        _reasoningText.value = r
                    }
                    parts.answer.let { a ->
                        answer.setLength(0)
                        answer.append(a)
                        _streamingText.value = a.ifEmpty { null }
                    }
                }
                _messages.update {
                    it + ChatMessage(
                        "assistant",
                        answer.toString(),
                        reasoning.toString().ifEmpty { null },
                    )
                }
                Log.d("EdgeDroid.Sample", "Answer (${answer.length}): ${answer}")
            } catch (t: Throwable) {
                _error.value = t.message ?: "Generation failed"
                Log.e("EdgeDroid.Sample", "Generation failed", t)
            } finally {
                _streamingText.value = null
                _reasoningText.value = null
                clearImage()
            }
        }
    }

    // ---- embeddings (ONNX embedding-capable models) ----

    fun runEmbeddings() {
        viewModelScope.launch {
            _embeddingResult.value = "Loading ONNX embedding model…"
            _error.value = null
            try {
                sdk.load()
                val cat = sdk.embeddings("A cat sits on a mat.")
                val dog = sdk.embeddings("A dog plays in the park.")
                val physics = sdk.embeddings("Quantum physics is fascinating.")
                val catDog = cosine(cat, dog)
                val catPhysics = cosine(cat, physics)
                Log.d("EdgeDroid.Sample", "Embeddings done: catDog=$catDog catPhysics=$catPhysics dim=${cat.size}")
                _embeddingResult.value = buildString {
                    appendLine("cat vs dog: ${"%.3f".format(catDog)}")
                    appendLine("cat vs physics: ${"%.3f".format(catPhysics)}")
                    appendLine("dim=${cat.size}")
                }
            } catch (t: Throwable) {
                _embeddingResult.value = null
                _error.value = "Embeddings failed: ${t.message}"
            }
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }

    // ---- model lifecycle ----

    fun checkCompatibility() {
        val report = sdk.models.checkCompatibility()
        val text = buildList {
            report.errors.forEach { add("ERROR: ${it.message}") }
            report.warnings.forEach { add("WARN: ${it.message}") }
        }.joinToString("\n")
        _compatibility.value = text.ifEmpty {
            "Compatible — downloadable: ${report.isDownloadable}, loadable: ${report.isLoadable}"
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            _downloadProgress.value = 0f
            _downloadError.value = null
            sdk.models.download().collect { state ->
                when (state) {
                    is ModelDownloadState.Downloading -> _downloadProgress.value = state.progress
                    is ModelDownloadState.Completed -> {
                        _downloadProgress.value = 1f
                        store.refreshDownloaded()
                        loadModel()
                    }
                    is ModelDownloadState.Failed -> {
                        _downloadProgress.value = null
                        _downloadError.value = "Download failed (${state.kind}): ${state.message}"
                    }
                    is ModelDownloadState.Cancelled -> _downloadProgress.value = null
                    else -> Unit
                }
            }
        }
    }

    fun retryDownload() = downloadModel()

    fun loadModel() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                sdk.load()
            } catch (t: Throwable) {
                _error.value = t.message ?: "Failed to load model"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            try {
                sdk.unload()
            } catch (t: Throwable) {
                _error.value = t.message ?: "Failed to unload model"
            }
        }
    }

    fun stop() {
        viewModelScope.launch { sdk.stop() }
    }

    fun clear() {
        sdk.resetChat()
        _messages.value = emptyList()
        _reasoningText.value = null
        _streamingText.value = null
    }
}
