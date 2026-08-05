package com.sgaikar1.edgedroid.core

/**
 * Renders structured chat history into a single model-ready prompt string. Templates are
 * swappable (ChatML, Qwen, Llama, raw) and chosen from model metadata — the SDK core does not
 * hardcode any vendor format.
 */
interface PromptProcessor {
    enum class Template { CHATML, QWEN, LLAMA, RAW }

    fun templateFor(model: Model): Template
    fun build(template: Template, messages: List<Message>, systemPrompt: String? = null): String

    data class Message(
        val role: Role,
        val content: String,
    ) {
        enum class Role { SYSTEM, USER, ASSISTANT }
    }
}
