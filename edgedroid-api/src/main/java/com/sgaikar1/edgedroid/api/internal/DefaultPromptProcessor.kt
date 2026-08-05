package com.sgaikar1.edgedroid.api.internal

import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.PromptProcessor

internal class DefaultPromptProcessor : PromptProcessor {

    override fun templateFor(model: Model): PromptProcessor.Template {
        return when (model.metadata["template"]?.lowercase()) {
            "qwen" -> PromptProcessor.Template.QWEN
            "llama", "llama3" -> PromptProcessor.Template.LLAMA
            "chatml" -> PromptProcessor.Template.CHATML
            "raw" -> PromptProcessor.Template.RAW
            else -> PromptProcessor.Template.CHATML
        }
    }

    override fun build(
        template: PromptProcessor.Template,
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): String = when (template) {
        PromptProcessor.Template.CHATML -> buildChatML(messages, systemPrompt)
        PromptProcessor.Template.QWEN -> buildQwen(messages, systemPrompt)
        PromptProcessor.Template.LLAMA -> buildLlama3(messages, systemPrompt)
        PromptProcessor.Template.RAW -> buildRaw(messages, systemPrompt)
    }

    private fun buildChatML(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): String {
        val sb = StringBuilder()
        systemPrompt?.let {
            sb.append("<|im_start|>system\n").append(it).append("<|im_end|>\n")
        }
        for (m in messages) {
            val role = when (m.role) {
                PromptProcessor.Message.Role.SYSTEM -> "system"
                PromptProcessor.Message.Role.USER -> "user"
                PromptProcessor.Message.Role.ASSISTANT -> "assistant"
            }
            sb.append("<|im_start|>$role\n").append(m.content).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun buildQwen(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): String {
        val sb = StringBuilder()
        systemPrompt?.let {
            sb.append("<|im_start|>system\n").append(it).append("<|im_end|>\n")
        }
        for (m in messages) {
            val role = when (m.role) {
                PromptProcessor.Message.Role.SYSTEM -> "system"
                PromptProcessor.Message.Role.USER -> "user"
                PromptProcessor.Message.Role.ASSISTANT -> "assistant"
            }
            sb.append("<|im_start|>$role\n").append(m.content).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun buildLlama3(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): String {
        val sb = StringBuilder("<|begin_of_text|>")
        systemPrompt?.let {
            sb.append("<|start_header_id|>system<|end_header_id|>\n\n")
                .append(it)
                .append("<|eot_id|>")
        }
        for (m in messages) {
            val header = when (m.role) {
                PromptProcessor.Message.Role.SYSTEM -> "system"
                PromptProcessor.Message.Role.USER -> "user"
                PromptProcessor.Message.Role.ASSISTANT -> "assistant"
            }
            sb.append("<|start_header_id|>$header<|end_header_id|>\n\n")
                .append(m.content)
                .append("<|eot_id|>")
        }
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    private fun buildRaw(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): String {
        val sb = StringBuilder()
        systemPrompt?.let { sb.append("System: ").append(it).append("\n") }
        for (m in messages) {
            val role = when (m.role) {
                PromptProcessor.Message.Role.SYSTEM -> "System"
                PromptProcessor.Message.Role.USER -> "User"
                PromptProcessor.Message.Role.ASSISTANT -> "Assistant"
            }
            sb.append(role).append(": ").append(m.content).append("\n")
        }
        sb.append("Assistant: ")
        return sb.toString()
    }
}
