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

    /**
     * Renders the prompt into a stable [PromptParts.prefix] (everything up to and including the
     * last user role opener) and the per-call [PromptParts.body] (the last user content plus the
     * assistant opener). Runtimes can cache the decoded KV of the prefix — it is identical
     * whenever [systemPrompt] and the history prefix are unchanged — and only re-decode the body.
     */
    fun buildParts(template: Template, messages: List<Message>, systemPrompt: String? = null): PromptParts

    data class Message(
        val role: Role,
        val content: String,
    ) {
        enum class Role { SYSTEM, USER, ASSISTANT }
    }

    data class PromptParts(
        val prefix: String,
        val body: String,
    ) {
        /** Backwards-compatible full prompt. */
        fun render(): String = prefix + body
    }
}
