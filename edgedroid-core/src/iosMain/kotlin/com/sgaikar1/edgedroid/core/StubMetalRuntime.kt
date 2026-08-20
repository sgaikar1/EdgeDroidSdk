package com.sgaikar1.edgedroid.core

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.Token
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Feasibility-spike runtime for iosArm64. This is **not** an inference backend: it exists to
 * prove the SPI surface ([Runtime], [RuntimePlugin], [LlmEngine] plus the `stream()`/`generate()`
 * path) compiles and links for Kotlin/Native on iOS with no Android-isms.
 *
 * The production backend is expected to be Metal/MetalFX (MPSGraph or Metal Performance
 * Shaders) driving quantized GGUF models through a small cinterop bridge; the [StubMetalPlugin]
 * keeps the exact registration contract a real `MetalRuntimePlugin` would implement.
 */
class StubMetalRuntime : Runtime {

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Uninitialized)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    override suspend fun initialize() {
        _state.value = RuntimeState.Initialized
    }

    override suspend fun loadModel(model: Model, options: RuntimeConfig): ModelHandle {
        _state.value = RuntimeState.ModelLoaded
        return 1L
    }

    override suspend fun unload(handle: ModelHandle) {
        _state.value = RuntimeState.Initialized
    }

    override suspend fun generate(
        handle: ModelHandle,
        prompt: PromptProcessor.PromptParts,
        options: GenerationOptions,
    ): Flow<Token> = flow {
        // Canned echo of the rendered prompt so a consumer can observe the whole stream pipeline.
        val text = prompt.render().ifBlank { "Hello from the EdgeDroid iOS stub runtime." }
        text.split(" ").forEachIndexed { index, word ->
            delay(1)
            emit(Token(index = index.toLong(), id = index.toLong(), text = "$word "))
        }
    }

    override suspend fun tokenize(handle: ModelHandle, text: String): List<Int> =
        text.map { it.code }

    override suspend fun embeddings(handle: ModelHandle, text: String): FloatArray =
        FloatArray(0)

    override suspend fun stop(handle: ModelHandle) = Unit
}