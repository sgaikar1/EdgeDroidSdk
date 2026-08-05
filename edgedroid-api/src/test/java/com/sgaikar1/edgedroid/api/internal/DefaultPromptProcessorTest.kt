package com.sgaikar1.edgedroid.api.internal

import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.PromptProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultPromptProcessorTest {

    private val processor = DefaultPromptProcessor()

    @Test
    fun `chatml template renders messages and opens the assistant turn`() {
        val rendered = processor.build(
            PromptProcessor.Template.CHATML,
            listOf(
                PromptProcessor.Message(PromptProcessor.Message.Role.USER, "Hello"),
            ),
            systemPrompt = "Be concise.",
        )
        assertEquals(
            "<|im_start|>system\nBe concise.<|im_end|>\n" +
                "<|im_start|>user\nHello<|im_end|>\n" +
                "<|im_start|>assistant\n",
            rendered,
        )
    }

    @Test
    fun `template is chosen from model metadata and defaults to chatml`() {
        assertEquals(
            PromptProcessor.Template.CHATML,
            processor.templateFor(Model.local("/tmp/model.gguf")),
        )
        assertEquals(
            PromptProcessor.Template.QWEN,
            processor.templateFor(
                Model.local("/tmp/model.gguf").copy(metadata = mapOf("template" to "qwen")),
            ),
        )
    }

    @Test
    fun `raw template uses plain role prefixes`() {
        val rendered = processor.build(
            PromptProcessor.Template.RAW,
            listOf(PromptProcessor.Message(PromptProcessor.Message.Role.USER, "Hi")),
        )
        assertEquals("User: Hi\nAssistant: ", rendered)
    }
}
