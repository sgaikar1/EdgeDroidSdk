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
        PromptProcessor.Template.QWEN -> buildChatML(messages, systemPrompt)
        PromptProcessor.Template.LLAMA -> buildLlama3(messages, systemPrompt)
        PromptProcessor.Template.RAW -> buildRaw(messages, systemPrompt)
    }

    override fun buildParts(
        template: PromptProcessor.Template,
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): PromptProcessor.PromptParts = when (template) {
        PromptProcessor.Template.CHATML, PromptProcessor.Template.QWEN -> chatmlParts(messages, systemPrompt)
        PromptProcessor.Template.LLAMA -> llama3Parts(messages, systemPrompt)
        PromptProcessor.Template.RAW -> rawParts(messages, systemPrompt)
    }

    private fun chatmlParts(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): PromptProcessor.PromptParts {
        val prefix = StringBuilder()
        systemPrompt?.let { prefix.append("<|im_start|>system\n").append(it).append("<|im_end|>\n") }
        messages.dropLast(1).forEach { m ->
            prefix.append("<|im_start|>${m.role.tag()}\n").append(m.content).append("<|im_end|>\n")
        }
        val last = messages.lastOrNull()
        if (last != null) {
            prefix.append("<|im_start|>${last.role.tag()}\n")
            val body = last.content + "<|im_end|>\n<|im_start|>assistant\n"
            return PromptProcessor.PromptParts(prefix.toString(), body)
        }
        return PromptProcessor.PromptParts(prefix.toString(), "<|im_start|>assistant\n")
    }

    private fun llama3Parts(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): PromptProcessor.PromptParts {
        val prefix = StringBuilder("<|begin_of_text|>")
        systemPrompt?.let {
            prefix.append("<|start_header_id|>system<|end_header_id|>\n\n").append(it).append("<|eot_id|>")
        }
        messages.dropLast(1).forEach { m ->
            prefix.append("<|start_header_id|>${m.role.tag()}<|end_header_id|>\n\n").append(m.content).append("<|eot_id|>")
        }
        val last = messages.lastOrNull()
        if (last != null) {
            prefix.append("<|start_header_id|>${last.role.tag()}<|end_header_id|>\n\n")
            val body = last.content + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
            return PromptProcessor.PromptParts(prefix.toString(), body)
        }
        return PromptProcessor.PromptParts(prefix.toString(), "<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    private fun rawParts(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): PromptProcessor.PromptParts {
        val prefix = StringBuilder()
        systemPrompt?.let { prefix.append("System: ").append(it).append("\n") }
        messages.dropLast(1).forEach { m ->
            prefix.append(m.role.tag().replaceFirstChar { it.uppercase() }).append(": ").append(m.content).append("\n")
        }
        val last = messages.lastOrNull()
        if (last != null) {
            prefix.append("User: ")
            return PromptProcessor.PromptParts(prefix.toString(), last.content + "\nAssistant: ")
        }
        return PromptProcessor.PromptParts(prefix.toString(), "Assistant: ")
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

private fun PromptProcessor.Message.Role.tag(): String = when (this) {
    PromptProcessor.Message.Role.SYSTEM -> "system"
    PromptProcessor.Message.Role.USER -> "user"
    PromptProcessor.Message.Role.ASSISTANT -> "assistant"
}
