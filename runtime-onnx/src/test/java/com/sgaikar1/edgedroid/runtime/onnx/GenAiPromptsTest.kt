package com.sgaikar1.edgedroid.runtime.onnx

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.core.PromptProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for the GenAI chat driver — the prompt-delta (KV-cache) computation and the
 * GenerationOptions -> GeneratorParams search-option mapping. These run on the JVM without the
 * ONNX Runtime GenAI native library.
 */
class GenAiPromptsTest {

    private fun parts(prefix: String, body: String) =
        PromptProcessor.PromptParts(prefix, body)

    @Test
    fun `first turn feeds the full rendered prompt`() {
        val p = parts("<|im_start|>system\nsys<|im_end|>\n<|im_start|>user\n", "hi<|im_end|>\n<|im_start|>assistant\n")
        assertEquals(
            "<|im_start|>system\nsys<|im_end|>\n<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n",
            GenAiPrompts.turnInput(p, PromptProcessor.Template.CHATML, isFirstTurn = true),
        )
    }

    @Test
    fun `continuing turn feeds only the delta to reuse KV`() {
        val p = parts("<|im_start|>system\nsys<|im_end|>\n<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\nok<|im_end|>\n<|im_start|>user\n", "how are you<|im_end|>\n<|im_start|>assistant\n")
        assertEquals(
            "<|im_start|>user\nhow are you<|im_end|>\n<|im_start|>assistant\n",
            GenAiPrompts.turnInput(p, PromptProcessor.Template.CHATML, isFirstTurn = false),
        )
    }

    @Test
    fun `user opener matches the template`() {
        assertEquals("<|im_start|>user\n", GenAiPrompts.userOpener(PromptProcessor.Template.CHATML))
        assertEquals("<|im_start|>user\n", GenAiPrompts.userOpener(PromptProcessor.Template.QWEN))
        assertEquals("<|start_header_id|>user<|end_header_id|>\n\n", GenAiPrompts.userOpener(PromptProcessor.Template.LLAMA))
        assertEquals("User: ", GenAiPrompts.userOpener(PromptProcessor.Template.RAW))
    }

    @Test
    fun `matchStop returns truncation point at the first stop sequence`() {
        assertEquals(5, GenAiPrompts.matchStop("help\nUSER:", listOf("USER:", "END")))
        assertEquals(5, GenAiPrompts.matchStop("help\nEND", listOf("USER:", "END")))
    }

    @Test
    fun `matchStop returns null when no stop sequence present`() {
        assertNull(GenAiPrompts.matchStop("just a normal answer", listOf("USER:", "END")))
        assertNull(GenAiPrompts.matchStop("anything", emptyList()))
    }

    @Test
    fun `options map wires temperature top-p max-tokens and seed`() {
        val mapped = GenAiOptions.map(
            GenerationOptions(temperature = 0.7f, topK = 40, topP = 0.9f, repeatPenalty = 1.2f, seed = 7, maxTokens = 512),
        )
        assertEquals(true, mapped.doSample)
        assertEquals(0.7, mapped.searchOptions["temperature"]!!, 1e-6)
        assertEquals(40.0, mapped.searchOptions["top_k"]!!, 1e-6)
        assertEquals(0.9, mapped.searchOptions["top_p"]!!, 1e-6)
        assertEquals(1.2, mapped.searchOptions["repetition_penalty"]!!, 1e-6)
        assertEquals(7.0, mapped.searchOptions["seed"]!!, 1e-6)
        assertEquals(512.0, mapped.searchOptions["max_length"]!!, 1e-6)
    }

    @Test
    fun `options map disables sampling for zero temperature`() {
        val mapped = GenAiOptions.map(GenerationOptions(temperature = 0f, topK = 40, topP = 0.9f))
        assertEquals(false, mapped.doSample)
        // temperature is skipped when it equals 1 or sampling is off.
        assertNull(mapped.searchOptions["temperature"])
        // max_length is always present.
        assertEquals(256.0, mapped.searchOptions["max_length"]!!, 1e-6)
    }

    @Test
    fun `options map omits seed when negative`() {
        val mapped = GenAiOptions.map(GenerationOptions(seed = -1))
        assertNull(mapped.searchOptions["seed"])
    }
}
