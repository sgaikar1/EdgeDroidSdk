package com.sgaikar1.edgedroid.core

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.Token
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface LlmEngineState {
    data object Idle : LlmEngineState
    data object Loading : LlmEngineState
    data object Ready : LlmEngineState
    data object Generating : LlmEngineState
    data object Unloading : LlmEngineState
    data class Error(val kind: String, val message: String) : LlmEngineState
}

/**
 * The SDK-facing orchestrator. Implemented inside the SDK; the app interacts with it through
 * [com.sgaikar1.edgedroid.api.LlmSdk]. It owns model resolution, download-on-demand, runtime
 * selection and sessions so the app never does.
 */
interface LlmEngine {
    suspend fun load()
    suspend fun unload()
    suspend fun generate(prompt: String, options: GenerationOptions): String
    fun stream(prompt: String, options: GenerationOptions): Flow<Token>
    suspend fun stop()
    val state: StateFlow<LlmEngineState>
}
