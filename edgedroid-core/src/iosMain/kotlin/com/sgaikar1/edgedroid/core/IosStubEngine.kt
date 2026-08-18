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
 */
class IosStubEngine(
    private val runtime: Runtime = StubMetalRuntime(),
) : LlmEngine {

    private val _state = MutableStateFlow<LlmEngineState>(LlmEngineState.Idle)
    override val state: StateFlow<LlmEngineState> = _state.asStateFlow()

    override suspend fun load() {
        runtime.initialize()
        _state.value = LlmEngineState.Ready
    }

    override suspend fun unload() {
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
        val parts = PromptProcessor.PromptParts(prefix = "", body = prompt, attachments = images)
        runtime.generate(handle = 1L, prompt = parts, options = options).collect { emit(it) }
    }

    override suspend fun embeddings(text: String): FloatArray = runtime.embeddings(1L, text)

    override suspend fun stop() {
        runtime.stop(1L)
        _state.value = LlmEngineState.Ready
    }
}