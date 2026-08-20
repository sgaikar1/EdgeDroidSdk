package com.sgaikar1.edgedroid.api.internal

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.common.TtsAudio
import com.sgaikar1.edgedroid.common.TtsSpeech
import com.sgaikar1.edgedroid.core.ChatSession
import com.sgaikar1.edgedroid.core.LlmEngine
import com.sgaikar1.edgedroid.core.LlmEngineState
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelHandle
import com.sgaikar1.edgedroid.core.ModelProvider
import com.sgaikar1.edgedroid.core.PromptProcessor
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimePlugin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

internal class SdkEngine(
    model: Model?,
    private val provider: ModelProvider,
    private val config: RuntimeConfig,
    private val createRuntime: suspend () -> Runtime,
    private val log: LogProvider,
    private val compatibilityGate: (Model) -> String? = { null },
    private val processor: PromptProcessor = DefaultPromptProcessor(),
    private val ttsPlugin: RuntimePlugin? = null,
    private val ttsModel: Model? = null,
) : LlmEngine {

    private val _state = MutableStateFlow<LlmEngineState>(LlmEngineState.Idle)
    override val state: StateFlow<LlmEngineState> = _state.asStateFlow()

    private var activeModel: Model? = model
    private var runtime: Runtime? = null
    private var handle: ModelHandle = 0L
    private var localPath: String? = null

    private var ttsRuntime: Runtime? = null
    private var ttsHandle: ModelHandle = 0L

    private val session: ChatSession = DefaultChatSession()

    val currentModel: Model?
        get() = activeModel

    fun switchModel(model: Model) {
        if (runtime != null) {
            throw IllegalStateException("Unload the current model before switching")
        }
        activeModel = model
        session.reset()
    }

    fun setSystemPrompt(prompt: String) {
        session.systemPrompt = prompt
    }

    val systemPrompt: String?
        get() = session.systemPrompt

    fun resetSession() {
        session.reset()
    }

    val template: PromptProcessor.Template
        get() = activeModel?.let { processor.templateFor(it) } ?: PromptProcessor.Template.CHATML

    override suspend fun load() {
        if (_state.value == LlmEngineState.Ready) return
        if (_state.value == LlmEngineState.Loading) return
        val model = activeModel
            ?: throw IllegalStateException("No model configured — pass .model(...) to the builder or call loadModel(file)")
        _state.value = LlmEngineState.Loading
        try {
            localPath = provider.getLocalPath(model)
            compatibilityGate(model)?.let { message ->
                throw RuntimeException("Model cannot be loaded on this device: $message")
            }
            val runtime = createRuntime()
            runtime.initialize()
            val resolvedModel = model.copy(
                metadata = model.metadata + mapOf("localPath" to localPath!!),
            )
            handle = runtime.loadModel(resolvedModel, config)
            this.runtime = runtime
            _state.value = LlmEngineState.Ready
            log.log(LogProvider.Level.INFO, TAG, "Model '${model.id}' loaded, handle=$handle")
        } catch (t: Throwable) {
            _state.value = LlmEngineState.Error("load", t.message ?: "unknown")
            throw t
        }
    }

    override suspend fun unload() {
        val r = runtime ?: return
        _state.value = LlmEngineState.Unloading
        try {
            if (handle != 0L) r.unload(handle)
        } finally {
            handle = 0L
            runtime = null
            unloadTts()
            _state.value = LlmEngineState.Idle
        }
    }

    private suspend fun unloadTts() {
        val r = ttsRuntime ?: return
        runCatching { if (ttsHandle != 0L) r.unload(ttsHandle) }
        ttsHandle = 0L
        ttsRuntime = null
    }

    override suspend fun generate(
        prompt: String,
        images: List<PromptProcessor.PromptAttachment>,
        options: GenerationOptions,
    ): String {
        val sb = StringBuilder()
        stream(prompt, images, options).collect { sb.append(it.text) }
        return sb.toString()
    }

    override fun stream(
        prompt: String,
        images: List<PromptProcessor.PromptAttachment>,
        options: GenerationOptions,
    ): Flow<Token> = flow {
        ensureReady()
        val r = runtime ?: throw IllegalStateException("Runtime not ready")
        val h = handle

        session.addUserMessage(prompt, images)
        val parts = session.buildPromptParts(processor, template)

        val sb = StringBuilder()
        r.generate(h, parts, options).collect { token ->
            sb.append(token.text)
            emit(token)
        }
        session.addAssistantMessage(sb.toString())
    }

    override suspend fun embeddings(text: String): FloatArray {
        ensureReady()
        val r = runtime ?: throw IllegalStateException("Runtime not ready")
        return r.embeddings(handle, text)
    }

    /** Whether an AUDIO (TTS) runtime was configured via `.tts(...)`. */
    val ttsAvailable: Boolean
        get() = ttsPlugin != null && ttsModel != null

    /**
     * Synthesize [text] using the configured TTS runtime (Capability.AUDIO). Lazily loads the
     * TTS model on first call. Throws [UnsupportedOperationException] if no TTS runtime/model
     * was configured.
     */
    suspend fun synthesize(text: String, voice: String, speed: Float): TtsAudio {
        val speech = ensureTtsReady() ?: throw UnsupportedOperationException(
            "No AUDIO runtime configured — register one with EdgeDroid.Builder.tts(plugin, model)",
        )
        return speech.synthesize(text, voice, speed)
    }

    private suspend fun ensureTtsReady(): TtsSpeech? {
        val plugin = ttsPlugin ?: return null
        val model = ttsModel ?: return null
        if (ttsRuntime != null) return ttsRuntime as? TtsSpeech
        val path = provider.getLocalPath(model)
        val runtime = plugin.create(config)
        runtime.initialize()
        ttsHandle = runtime.loadModel(
            model.copy(metadata = model.metadata + mapOf("localPath" to path)),
            config,
        )
        ttsRuntime = runtime
        return runtime as? TtsSpeech
    }

    override suspend fun stop() {
        val r = runtime
        if (r != null && handle != 0L) {
            r.stop(handle)
            _state.value = LlmEngineState.Ready
        }
    }

    private suspend fun ensureReady() {
        if (_state.value != LlmEngineState.Ready) load()
    }

    companion object {
        private const val TAG = "EdgeDroid.SdkEngine"
    }
}
