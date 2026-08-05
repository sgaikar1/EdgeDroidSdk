package com.sgaikar1.edgedroid.api.internal

import com.sgaikar1.edgedroid.core.ChatSession
import com.sgaikar1.edgedroid.core.PromptProcessor

internal class DefaultChatSession : ChatSession {

    private val messages = mutableListOf<PromptProcessor.Message>()

    override var systemPrompt: String? = null

    override val history: List<PromptProcessor.Message>
        get() = messages.toList()

    override fun addUserMessage(text: String) {
        messages.add(PromptProcessor.Message(PromptProcessor.Message.Role.USER, text))
    }

    override fun addAssistantMessage(text: String) {
        messages.add(PromptProcessor.Message(PromptProcessor.Message.Role.ASSISTANT, text))
    }

    override fun reset() {
        messages.clear()
        systemPrompt = null
    }

    override fun buildPrompt(
        processor: PromptProcessor,
        template: PromptProcessor.Template,
    ): String = processor.build(template, history, systemPrompt)
}
