package com.sgaikar1.edgedroid.runtime.executorch

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.MemoryConfig
import com.sgaikar1.edgedroid.core.RuntimeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pytorch.executorch.extension.llm.LlmModule
import org.pytorch.executorch.extension.llm.LlmModuleConfig

/**
 * Pure-JVM tests for the RuntimeConfig -> ExecuTorch config transformer. No ExecuTorch native
 * library is loaded; the Android AAR's Kotlin classes (LlmModuleConfig/LlmGenerationConfig) are
 * plain objects until a native method is called.
 */
class ExecuTorchConfigTransformerTest {

    private val model = Model(
        id = "tiny-pte",
        name = "Tiny PTE",
        format = ModelFormat.PTE,
        metadata = mapOf(
            "localPath" to "/data/edge/models/tiny.pte",
            "tokenizerPath" to "/data/edge/models/tokenizer.bin",
        ),
    )

    private val baseConfig = RuntimeConfig(memory = MemoryConfig(mmap = true))

    @Test
    fun `moduleConfig maps localPath and tokenizerPath with mmap default`() {
        val cfg = ExecuTorchConfigTransformer.moduleConfig(model, baseConfig)

        assertEquals("/data/edge/models/tiny.pte", cfg.modulePath)
        assertEquals("/data/edge/models/tokenizer.bin", cfg.tokenizerPath)
        assertEquals(LlmModuleConfig.LOAD_MODE_MMAP, cfg.loadMode)
        assertEquals(LlmModule.MODEL_TYPE_TEXT, cfg.modelType)
        assertEquals(0.8f, cfg.temperature, 0.0f)
    }

    @Test
    fun `moduleConfig throws without a tokenizer path`() {
        val noTokenizer = Model(
            id = "bad",
            name = "Bad",
            format = ModelFormat.PTE,
            metadata = mapOf("localPath" to "/data/edge/models/bad.pte"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ExecuTorchConfigTransformer.moduleConfig(noTokenizer, baseConfig)
        }
    }

    @Test
    fun `moduleConfig throws without a local path`() {
        val remote = Model(id = "remote", name = "Remote", format = ModelFormat.PTE)
        assertThrows(IllegalArgumentException::class.java) {
            ExecuTorchConfigTransformer.moduleConfig(remote, baseConfig)
        }
    }

    @Test
    fun `loadMode honors mmap=false and extras override`() {
        val noMmap = RuntimeConfig(memory = MemoryConfig(mmap = false))
        assertEquals(
            LlmModuleConfig.LOAD_MODE_FILE,
            ExecuTorchConfigTransformer.moduleConfig(model, noMmap).loadMode,
        )

        val fileOverride = RuntimeConfig(
            memory = MemoryConfig(mmap = true),
            extras = mapOf(ExecuTorchConfigTransformer.KEY_LOAD_MODE to "file"),
        )
        assertEquals(
            LlmModuleConfig.LOAD_MODE_FILE,
            ExecuTorchConfigTransformer.moduleConfig(model, fileOverride).loadMode,
        )

        val mlock = RuntimeConfig(
            extras = mapOf(ExecuTorchConfigTransformer.KEY_LOAD_MODE to "mlock"),
        )
        assertEquals(
            LlmModuleConfig.LOAD_MODE_MMAP_USE_MLOCK,
            ExecuTorchConfigTransformer.moduleConfig(model, mlock).loadMode,
        )
    }

    @Test
    fun `modelType selects text-vision from metadata`() {
        val vision = model.copy(metadata = model.metadata + ("modelType" to "vision"))
        assertEquals(
            LlmModule.MODEL_TYPE_TEXT_VISION,
            ExecuTorchConfigTransformer.moduleConfig(vision, baseConfig).modelType,
        )
        val explicitTwo = model.copy(metadata = model.metadata + ("modelType" to "2"))
        assertEquals(
            LlmModule.MODEL_TYPE_TEXT_VISION,
            ExecuTorchConfigTransformer.moduleConfig(explicitTwo, baseConfig).modelType,
        )
    }

    @Test
    fun `generationConfig maps options and sizes seqLen to the context window`() {
        val cfg = ExecuTorchConfigTransformer.generationConfig(
            options = GenerationOptions(temperature = 0.9f, maxTokens = 64),
            promptChars = 400, // ~100 estimated prompt tokens
            contextSize = 4096,
        )

        assertEquals(0.9f, cfg.temperature, 0.0f)
        assertEquals(64, cfg.maxNewTokens)
        assertTrue("seqLen must leave room for maxTokens", cfg.seqLen >= 64)
        assertTrue("seqLen must fit the context window", cfg.seqLen <= 4096)
        assertEquals(false, cfg.echo)
    }

    @Test
    fun `generationConfig is cached per input and invalidated on clearCache`() {
        val a = ExecuTorchConfigTransformer.generationConfig(
            GenerationOptions(maxTokens = 32), promptChars = 100, contextSize = 2048,
        )
        val b = ExecuTorchConfigTransformer.generationConfig(
            GenerationOptions(maxTokens = 32), promptChars = 100, contextSize = 2048,
        )
        assertSame("identical inputs reuse the cached config", a, b)

        val c = ExecuTorchConfigTransformer.generationConfig(
            GenerationOptions(maxTokens = 48), promptChars = 100, contextSize = 2048,
        )
        assertNotSame("different options build a new config", a, c)

        ExecuTorchConfigTransformer.clearCache()
        val d = ExecuTorchConfigTransformer.generationConfig(
            GenerationOptions(maxTokens = 32), promptChars = 100, contextSize = 2048,
        )
        assertNotSame("clearCache invalidates the cache", a, d)
    }

    @Test
    fun `generationConfig never exceeds the context window`() {
        // Even when the requested new-token budget exceeds the window, seqLen stays inside it.
        val cfg = ExecuTorchConfigTransformer.generationConfig(
            options = GenerationOptions(maxTokens = 256),
            promptChars = 0,
            contextSize = 128,
        )
        assertTrue("seqLen must never exceed contextSize", cfg.seqLen <= 128)
    }

    @Test
    fun `moduleConfig is cached per model and config`() {
        val a = ExecuTorchConfigTransformer.moduleConfig(model, baseConfig)
        val b = ExecuTorchConfigTransformer.moduleConfig(model, baseConfig)
        assertSame(a, b)

        val different = RuntimeConfig(memory = MemoryConfig(mmap = false))
        val c = ExecuTorchConfigTransformer.moduleConfig(model, different)
        assertNotSame(a, c)
        assertEquals(LlmModuleConfig.LOAD_MODE_FILE, c.loadMode)
    }
}