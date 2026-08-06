package com.sgaikar1.edgedroid.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.core.ModelDownloadState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.sgaikar1.edgedroid.core.LlmEngineState

data class ChatMessage(val role: String, val text: String)

class ChatViewModel(
    private val app: EdgeDroidApp,
) : ViewModel() {

    private val sdk = app.sdk

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _compatibility = MutableStateFlow<String?>(null)
    val compatibility: StateFlow<String?> = _compatibility.asStateFlow()

    val engineState: StateFlow<LlmEngineState> = sdk.state

    private var generationJob: Job? = null

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
            sdk.models.download().collect { state ->
                when (state) {
                    is ModelDownloadState.Downloading -> _downloadProgress.value = state.progress
                    is ModelDownloadState.Completed -> {
                        _downloadProgress.value = 1f
                        loadModel()
                    }
                    is ModelDownloadState.Failed -> _error.value = "Download failed: ${state.message}"
                    is ModelDownloadState.Cancelled -> _downloadProgress.value = null
                    else -> Unit
                }
            }
        }
    }

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

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty() || generationJob?.isActive == true) return

        _messages.update { it + ChatMessage("user", text) }
        _streamingText.value = ""
        val partial = StringBuilder()

        generationJob = viewModelScope.launch {
            try {
                sdk.stream(text) { token: Token ->
                    partial.append(token.text)
                    _streamingText.value = partial.toString()
                }
                _messages.update { it + ChatMessage("assistant", partial.toString()) }
            } catch (t: Throwable) {
                _error.value = t.message ?: "Generation failed"
            } finally {
                _streamingText.value = null
            }
        }
    }

    fun stop() {
        viewModelScope.launch { sdk.stop() }
    }

    fun clear() {
        sdk.resetChat()
        _messages.value = emptyList()
    }
}
