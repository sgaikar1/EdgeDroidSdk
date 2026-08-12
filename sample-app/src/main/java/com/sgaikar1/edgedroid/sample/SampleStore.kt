package com.sgaikar1.edgedroid.sample

import android.content.Context
import com.sgaikar1.edgedroid.api.LlmSdk
import com.sgaikar1.edgedroid.core.LlmEngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the single active [LlmSdk] and the [SampleConfig] that produced it. Applying a new
 * config rebuilds the SDK (unloading the previous one) — the chat/embedding UI always talks
 * to [sdk] and observes the current engine state via [sdkState].
 */
class SampleStore(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _config = MutableStateFlow(SampleConfig())
    val config: StateFlow<SampleConfig> = _config.asStateFlow()

    private val _sdkState = MutableStateFlow<LlmEngineState>(LlmEngineState.Idle)
    val sdkState: StateFlow<LlmEngineState> = _sdkState.asStateFlow()

    @Volatile
    var sdk: LlmSdk = SdkFactory.build(context.applicationContext, _config.value)
        private set

    private var stateJob: Job? = null

    init {
        subscribeState()
    }

    fun apply(newConfig: SampleConfig) {
        val old = sdk
        _config.value = newConfig
        sdk = SdkFactory.build(context.applicationContext, newConfig)
        sdk.systemPrompt = newConfig.systemPrompt
        subscribeState()
        scope.launch { runCatching { old.unload() } }
    }

    private fun subscribeState() {
        stateJob?.cancel()
        stateJob = scope.launch { sdk.state.collect { _sdkState.value = it } }
    }
}
