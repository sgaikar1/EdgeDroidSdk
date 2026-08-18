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

    // ---------------------------------------------------------------------------------------------
    // Gemma
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `gemma template renders a full multi-turn dialogue with system prompt`() {
        val rendered = processor.build(
            PromptProcessor.Template.GEMMA,
            fullDialogue(),
            systemPrompt = SYSTEM_PROMPT,
        )
        assertEquals(
            "<bos>" +
                "<start_of_turn>system\nYou are a helpful assistant.<end_of_turn>\n" +
                "<start_of_turn>user\nHello there!<end_of_turn>\n" +
                "<start_of_turn>model\nHi! How can I help?<end_of_turn>\n" +
                "<start_of_turn>user\nWhat is 2+2?<end_of_turn>\n" +
                "<start_of_turn>model\n4, I think.<end_of_turn>\n" +
                "<start_of_turn>user\nThanks!<end_of_turn>\n" +
                "<start_of_turn>model\n",
            rendered,
        )
    }

    @Test
    fun `gemma template splits prefix and body at the last user turn`() {
        val parts = processor.buildParts(
            PromptProcessor.Template.GEMMA,
            tripleTurn(),
            systemPrompt = SYSTEM_PROMPT,
        )
        assertEquals(
            "<bos>" +
                "<start_of_turn>system\nYou are a helpful assistant.<end_of_turn>\n" +
                "<start_of_turn>user\nHello there!<end_of_turn>\n" +
                "<start_of_turn>model\nHi! How can I help?<end_of_turn>\n" +
                "<start_of_turn>user\n",
            parts.prefix,
        )
        assertEquals("What is 2+2?<end_of_turn>\n<start_of_turn>model\n", parts.body)
        assertEquals(parts.prefix + parts.body, processor.build(PromptProcessor.Template.GEMMA, tripleTurn(), SYSTEM_PROMPT))
    }

    @Test
    fun `gemma renders a single user turn with no system prompt`() {
        val rendered = processor.build(
            PromptProcessor.Template.GEMMA,
            listOf(PromptProcessor.Message(PromptProcessor.Message.Role.USER, "Hello")),
        )
        assertEquals(
            "<bos><start_of_turn>user\nHello<end_of_turn>\n<start_of_turn>model\n",
            rendered,
        )
    }

    @Test
    fun `gemma template is selected from metadata aliases`() {
        for (key in listOf("gemma", "gemma2", "gemma3")) {
            assertEquals(
                PromptProcessor.Template.GEMMA,
                processor.templateFor(Model.local("/tmp/m.gguf").copy(metadata = mapOf("template" to key))),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Mistral
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `mistral template renders a full multi-turn dialogue with merged system prompt`() {
        val rendered = processor.build(
            PromptProcessor.Template.MISTRAL,
            fullDialogue(),
            systemPrompt = SYSTEM_PROMPT,
        )
        assertEquals(
            "<s> [INST] You are a helpful assistant.\n\nHello there! [/INST] Hi! How can I help?</s>" +
                " [INST] What is 2+2? [/INST] 4, I think.</s> [INST] Thanks! [/INST]",
            rendered,
        )
    }

    @Test
    fun `mistral template splits prefix and body at the last user turn`() {
        val parts = processor.buildParts(
            PromptProcessor.Template.MISTRAL,
            tripleTurn(),
            systemPrompt = SYSTEM_PROMPT,
        )
        assertEquals(
            "<s> [INST] You are a helpful assistant.\n\nHello there! [/INST] Hi! How can I help?</s> [INST] ",
            parts.prefix,
        )
        assertEquals("What is 2+2? [/INST]", parts.body)
        assertEquals(parts.prefix + parts.body, processor.build(PromptProcessor.Template.MISTRAL, tripleTurn(), SYSTEM_PROMPT))
    }

    @Test
    fun `mistral renders a single user turn without leading assistant space`() {
        val rendered = processor.build(
            PromptProcessor.Template.MISTRAL,
            listOf(PromptProcessor.Message(PromptProcessor.Message.Role.USER, "Hello")),
        )
        assertEquals("<s> [INST] Hello [/INST]", rendered)
    }

    @Test
    fun `mistral template is selected from metadata aliases`() {
        for (key in listOf("mistral", "mistral7b", "mistral-instruct")) {
            assertEquals(
                PromptProcessor.Template.MISTRAL,
                processor.templateFor(Model.local("/tmp/m.gguf").copy(metadata = mapOf("template" to key))),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Phi
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `phi template renders a full multi-turn dialogue with system prompt`() {
        val rendered = processor.build(
            PromptProcessor.Template.PHI,
            fullDialogue(),
            systemPrompt = SYSTEM_PROMPT,
        )
        assertEquals(
            "<|system|>\nYou are a helpful assistant.<|end|>\n" +
                "<|user|>\nHello there!<|end|>\n" +
                "<|assistant|>\nHi! How can I help?<|end|>\n" +
                "<|user|>\nWhat is 2+2?<|end|>\n" +
                "<|assistant|>\n4, I think.<|end|>\n" +
                "<|user|>\nThanks!<|end|>\n" +
                "<|assistant|>\n",
            rendered,
        )
    }

    @Test
    fun `phi template splits prefix and body at the last user turn`() {
        val parts = processor.buildParts(
            PromptProcessor.Template.PHI,
            tripleTurn(),
            systemPrompt = SYSTEM_PROMPT,
        )
        assertEquals(
            "<|system|>\nYou are a helpful assistant.<|end|>\n" +
                "<|user|>\nHello there!<|end|>\n" +
                "<|assistant|>\nHi! How can I help?<|end|>\n" +
                "<|user|>\n",
            parts.prefix,
        )
        assertEquals("What is 2+2?<|end|>\n<|assistant|>\n", parts.body)
        assertEquals(parts.prefix + parts.body, processor.build(PromptProcessor.Template.PHI, tripleTurn(), SYSTEM_PROMPT))
    }

    @Test
    fun `phi renders a single user turn without system prompt`() {
        val rendered = processor.build(
            PromptProcessor.Template.PHI,
            listOf(PromptProcessor.Message(PromptProcessor.Message.Role.USER, "Hello")),
        )
        assertEquals("<|user|>\nHello<|end|>\n<|assistant|>\n", rendered)
    }

    @Test
    fun `phi template is selected from metadata aliases`() {
        for (key in listOf("phi", "phi3", "phi3-mini", "phi4")) {
            assertEquals(
                PromptProcessor.Template.PHI,
                processor.templateFor(Model.local("/tmp/m.gguf").copy(metadata = mapOf("template" to key))),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // DeepSeek
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `deepseek template renders a full multi-turn dialogue with system prompt`() {
        val rendered = processor.build(
            PromptProcessor.Template.DEEPSEEK,
            fullDialogue(),
            systemPrompt = SYSTEM_PROMPT,
        )
        assertEquals(
            BOS + "You are a helpful assistant." +
                "<｜User｜>Hello there!<｜Assistant｜>Hi! How can I help?" + EOS +
                "<｜User｜>What is 2+2?<｜Assistant｜>4, I think." + EOS +
                "<｜User｜>Thanks!<｜Assistant｜>",
            rendered,
        )
    }

    @Test
    fun `deepseek template splits prefix and body at the last user turn`() {
        val parts = processor.buildParts(
            PromptProcessor.Template.DEEPSEEK,
            tripleTurn(),
            systemPrompt = SYSTEM_PROMPT,
        )
        assertEquals(
            BOS + "You are a helpful assistant." +
                "<｜User｜>Hello there!<｜Assistant｜>Hi! How can I help?" + EOS + "<｜User｜>",
            parts.prefix,
        )
        assertEquals("What is 2+2?<｜Assistant｜>", parts.body)
        assertEquals(parts.prefix + parts.body, processor.build(PromptProcessor.Template.DEEPSEEK, tripleTurn(), SYSTEM_PROMPT))
    }

    @Test
    fun `deepseek renders a single user turn without system prompt`() {
        val rendered = processor.build(
            PromptProcessor.Template.DEEPSEEK,
            listOf(PromptProcessor.Message(PromptProcessor.Message.Role.USER, "Hello")),
        )
        assertEquals(BOS + "<｜User｜>Hello<｜Assistant｜>", rendered)
    }

    @Test
    fun `deepseek template is selected from metadata aliases`() {
        for (key in listOf("deepseek", "deepseek-v2", "deepseek-v3", "deepseek-r1")) {
            assertEquals(
                PromptProcessor.Template.DEEPSEEK,
                processor.templateFor(Model.local("/tmp/m.gguf").copy(metadata = mapOf("template" to key))),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Aya
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `aya template renders a full multi-turn dialogue with system prompt`() {
        val rendered = processor.build(
            PromptProcessor.Template.AYA,
            fullDialogue(),
            systemPrompt = SYSTEM_PROMPT,
        )
        assertEquals(
            "<|START_OF_TURN_TOKEN|><|SYSTEM_TOKEN|>You are a helpful assistant.<|END_OF_TURN_TOKEN|>" +
                "<|START_OF_TURN_TOKEN|><|USER_TOKEN|>Hello there!<|END_OF_TURN_TOKEN|>" +
                "<|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>Hi! How can I help?<|END_OF_TURN_TOKEN|>" +
                "<|START_OF_TURN_TOKEN|><|USER_TOKEN|>What is 2+2?<|END_OF_TURN_TOKEN|>" +
                "<|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>4, I think.<|END_OF_TURN_TOKEN|>" +
                "<|START_OF_TURN_TOKEN|><|USER_TOKEN|>Thanks!<|END_OF_TURN_TOKEN|>" +
                "<|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>",
            rendered,
        )
    }

    @Test
    fun `aya template splits prefix and body at the last user turn`() {
        val parts = processor.buildParts(
            PromptProcessor.Template.AYA,
            tripleTurn(),
            systemPrompt = SYSTEM_PROMPT,
        )
        assertEquals(
            "<|START_OF_TURN_TOKEN|><|SYSTEM_TOKEN|>You are a helpful assistant.<|END_OF_TURN_TOKEN|>" +
                "<|START_OF_TURN_TOKEN|><|USER_TOKEN|>Hello there!<|END_OF_TURN_TOKEN|>" +
                "<|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>Hi! How can I help?<|END_OF_TURN_TOKEN|>" +
                "<|START_OF_TURN_TOKEN|><|USER_TOKEN|>",
            parts.prefix,
        )
        assertEquals(
            "What is 2+2?<|END_OF_TURN_TOKEN|><|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>",
            parts.body,
        )
        assertEquals(parts.prefix + parts.body, processor.build(PromptProcessor.Template.AYA, tripleTurn(), SYSTEM_PROMPT))
    }

    @Test
    fun `aya renders a single user turn without system prompt`() {
        val rendered = processor.build(
            PromptProcessor.Template.AYA,
            listOf(PromptProcessor.Message(PromptProcessor.Message.Role.USER, "Hello")),
        )
        assertEquals(
            "<|START_OF_TURN_TOKEN|><|USER_TOKEN|>Hello<|END_OF_TURN_TOKEN|>" +
                "<|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>",
            rendered,
        )
    }

    @Test
    fun `aya template is selected from metadata aliases`() {
        for (key in listOf("aya", "aya-expanse")) {
            assertEquals(
                PromptProcessor.Template.AYA,
                processor.templateFor(Model.local("/tmp/m.gguf").copy(metadata = mapOf("template" to key))),
            )
        }
    }

    private fun tripleTurn(): List<PromptProcessor.Message> = listOf(
        PromptProcessor.Message(PromptProcessor.Message.Role.USER, "Hello there!"),
        PromptProcessor.Message(PromptProcessor.Message.Role.ASSISTANT, "Hi! How can I help?"),
        PromptProcessor.Message(PromptProcessor.Message.Role.USER, "What is 2+2?"),
    )

    private fun fullDialogue(): List<PromptProcessor.Message> = listOf(
        PromptProcessor.Message(PromptProcessor.Message.Role.USER, "Hello there!"),
        PromptProcessor.Message(PromptProcessor.Message.Role.ASSISTANT, "Hi! How can I help?"),
        PromptProcessor.Message(PromptProcessor.Message.Role.USER, "What is 2+2?"),
        PromptProcessor.Message(PromptProcessor.Message.Role.ASSISTANT, "4, I think."),
        PromptProcessor.Message(PromptProcessor.Message.Role.USER, "Thanks!"),
    )

    companion object {
        private const val SYSTEM_PROMPT = "You are a helpful assistant."
        private const val BOS = "<｜begin▁of▁sentence｜>"
        private const val EOS = "<｜end▁of▁sentence｜>"
    }
}
