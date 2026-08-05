package com.sgaikar1.edgedroid.core

/**
 * Stateful conversation. Owns history, system prompt and future tool state; presents the runtime
 * with one prompt string via [buildPrompt]. Streaming/merge logic lives in the SDK engine so the
 * session stays a pure data holder.
 */
interface ChatSession {
    val history: List<PromptProcessor.Message>
    var systemPrompt: String?

    fun addUserMessage(text: String)
    fun addAssistantMessage(text: String)
    fun reset()

    fun buildPrompt(processor: PromptProcessor, template: PromptProcessor.Template): String
}
