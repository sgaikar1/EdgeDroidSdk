package com.sgaikar1.edgedroid.core

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.Token
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * Minimal [LlmEngine] for the iOS spike: proves the public `stream()` / `generate()` entry
 * points (the same surface `EdgeDroid` exposes on Android) compile for iosArm64 against the
 * stub runtime. The production iOS engine will mirror `edgedroid-api`'s `SdkEngine` (chat
 * session, prompt parts, download-on-demand) on top of a Metal backend.
 *
 * Lifecycle mirrors the Android engine: [load] runs `initialize()` **and** `loadModel()`, and
 * every streaming/embedding call requires the resulting non-zero [ModelHandle] — no hardcoded
 * handles. A production Metal runtime will reject calls with an unloaded handle, so the stub
 * keeps the same contract.
 */
class IosStubEngine(
    private val runtime: Runtime = StubMetalRuntime(),
    private val model: Model = Model.remote(id = "stub-model", url = ""),
) : LlmEngine {

    private val _state = MutableStateFlow<LlmEngineState>(LlmEngineState.Idle)
    override val state: StateFlow<LlmEngineState> = _state.asStateFlow()

    private var handle: ModelHandle = 0L

    override suspend fun load() {
        runtime.initialize()
        handle = runtime.loadModel(model, RuntimeConfig())
        _state.value = LlmEngineState.Ready
    }

    override suspend fun unload() {
        if (handle != 0L) {
            runtime.unload(handle)
            handle = 0L
        }
        _state.value = LlmEngineState.Idle
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
        val h = requireLoaded()
        val parts = PromptProcessor.PromptParts(prefix = "", body = prompt, attachments = images)
        runtime.generate(handle = h, prompt = parts, options = options).collect { emit(it) }
    }

    override suspend fun embeddings(text: String): FloatArray =
        runtime.embeddings(requireLoaded(), text)

    override suspend fun stop() {
        if (handle != 0L) {
            runtime.stop(handle)
            _state.value = LlmEngineState.Ready
        }
    }

    private fun requireLoaded(): ModelHandle {
        if (handle == 0L) {
            error("Model not loaded — call load() before stream()/generate()/embeddings()")
        }
        return handle
    }
}