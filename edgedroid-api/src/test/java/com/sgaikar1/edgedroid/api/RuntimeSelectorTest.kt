package com.sgaikar1.edgedroid.api

import com.sgaikar1.edgedroid.common.FailureKind
import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.common.SdkResult
import com.sgaikar1.edgedroid.common.Token
import com.sgaikar1.edgedroid.core.Capability
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.ModelHandle
import com.sgaikar1.edgedroid.core.PromptProcessor
import com.sgaikar1.edgedroid.core.Runtime
import com.sgaikar1.edgedroid.core.RuntimeConfig
import com.sgaikar1.edgedroid.core.RuntimePlugin
import com.sgaikar1.edgedroid.core.RuntimeState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the architectural cornerstone: a runtime is picked purely through the plugin SPI.
 * Adding ExecuTorch later = registering another [RuntimePlugin]; the SDK core never changes.
 */
class RuntimeSelectorTest {

    private val llamaModel = Model.remote(
        id = "qwen3-0.6b",
        url = "https://example.com/qwen.gguf",
        format = ModelFormat.GGUF,
    )

    private class FakeLlamaPlugin : RuntimePlugin {
        override val id = "llama"
        override val version = "test"
        override val supportedFormats = setOf(ModelFormat.GGUF)
        override val capabilities = setOf(Capability.STREAMING)
        override suspend fun create(config: RuntimeConfig): Runtime = FakeRuntime("llama")
    }

    private class FakeExecPlugin : RuntimePlugin {
        override val id = "executorch"
        override val version = "test"
        override val supportedFormats = setOf(ModelFormat.PTE)
        override val capabilities = setOf(Capability.STREAMING)
        override suspend fun create(config: RuntimeConfig): Runtime = FakeRuntime("executorch")
    }

    private class FakeRuntime(private val name: String) : Runtime {
        private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Uninitialized)
        override val state: StateFlow<RuntimeState> = _state.asStateFlow()
        override suspend fun initialize() { _state.value = RuntimeState.Initialized }
        override suspend fun loadModel(model: Model, options: RuntimeConfig): ModelHandle = 1L
        override suspend fun unload(handle: ModelHandle) {}
        override suspend fun generate(handle: ModelHandle, prompt: PromptProcessor.PromptParts, options: GenerationOptions): Flow<Token> =
            flow { emit(Token(0, 0, "$name:${options.maxTokens}")) }
        override suspend fun tokenize(handle: ModelHandle, text: String): List<Int> = emptyList()
        override suspend fun embeddings(handle: ModelHandle, text: String): FloatArray = floatArrayOf()
        override suspend fun stop(handle: ModelHandle) {}
    }

    @Test
    fun `AUTO selects the plugin supporting the model format`() = runTest {
        val registry = RuntimeRegistry()
        registry.register(FakeLlamaPlugin())
        registry.register(FakeExecPlugin())

        val result = RuntimeSelector.select(registry, RuntimeSpec.Auto, llamaModel, RuntimeConfig())
        assertTrue(result is SdkResult.Success)

        val token = (result as SdkResult.Success).value.generate(1L, PromptProcessor.PromptParts("p", "x"), GenerationOptions()).first()
        assertEquals("llama:256", token.text)
    }

    @Test
    fun `PTE model selects the executorch plugin under AUTO`() = runTest {
        val registry = RuntimeRegistry()
        registry.register(FakeLlamaPlugin())
        registry.register(FakeExecPlugin())

        val pteModel = llamaModel.copy(format = ModelFormat.PTE)
        val result = RuntimeSelector.select(registry, RuntimeSpec.Auto, pteModel, RuntimeConfig())
        assertTrue(result is SdkResult.Success)
        val token = (result as SdkResult.Success).value.generate(1L, PromptProcessor.PromptParts("p", "x"), GenerationOptions()).first()
        assertEquals("executorch:256", token.text)
    }

    @Test
    fun `ByPlugin creates the explicitly provided runtime`() = runTest {
        val registry = RuntimeRegistry()
        registry.register(FakeLlamaPlugin())

        val plugin = FakeExecPlugin()
        val result = RuntimeSelector.select(registry, RuntimeSpec.ByPlugin(plugin), llamaModel, RuntimeConfig())
        assertTrue(result is SdkResult.Success)
        val token = (result as SdkResult.Success).value.generate(1L, PromptProcessor.PromptParts("p", "x"), GenerationOptions()).first()
        assertEquals("executorch:256", token.text)
    }

    @Test
    fun `AUTO with no matching plugin returns RUNTIME_NOT_FOUND`() = runTest {
        val registry = RuntimeRegistry()
        registry.register(FakeLlamaPlugin())

        val onnxModel = llamaModel.copy(format = ModelFormat.ONNX)
        val result = RuntimeSelector.select(registry, RuntimeSpec.Auto, onnxModel, RuntimeConfig())
        assertTrue(result is SdkResult.Failure)
        assertEquals(FailureKind.RUNTIME_NOT_FOUND, (result as SdkResult.Failure).kind)
    }
}
