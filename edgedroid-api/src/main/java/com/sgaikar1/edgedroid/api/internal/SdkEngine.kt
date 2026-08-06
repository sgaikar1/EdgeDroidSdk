package com.sgaikar1.edgedroid.api.internal

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.LogProvider
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.core.ChatSession
import com.sgaikar1.edgedroid.core.LlmEngine
import com.sgaikar1.edgedroid.core.LlmEngineState
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelHandle
import com.sgaikar1.edgedroid.core.ModelProvider
import com.sgaikar1.edgedroid.core.PromptProcessor
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
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
) : LlmEngine {

    private val _state = MutableStateFlow<LlmEngineState>(LlmEngineState.Idle)
    override val state: StateFlow<LlmEngineState> = _state.asStateFlow()

    private var activeModel: Model? = model
    private var runtime: Runtime? = null
    private var handle: ModelHandle = 0L
    private var localPath: String? = null

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
            _state.value = LlmEngineState.Idle
        }
    }

    override suspend fun generate(prompt: String, options: GenerationOptions): String {
        val sb = StringBuilder()
        stream(prompt, options).collect { sb.append(it.text) }
        return sb.toString()
    }

    override fun stream(prompt: String, options: GenerationOptions): Flow<Token> = flow {
        ensureReady()
        val r = runtime ?: throw IllegalStateException("Runtime not ready")
        val h = handle

        session.addUserMessage(prompt)
        val rendered = session.buildPrompt(processor, template)

        val sb = StringBuilder()
        r.generate(h, rendered, options).collect { token ->
            sb.append(token.text)
            emit(token)
        }
        session.addAssistantMessage(sb.toString())
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
