package com.sgaikar1.edgedroid.api.internal

import com.sgaikar1.edgedroid.core.Model
import com.sgaikar1.edgedroid.core.PromptProcessor

internal class DefaultPromptProcessor : PromptProcessor {

    override fun templateFor(model: Model): PromptProcessor.Template {
        return when (model.metadata["template"]?.lowercase()) {
            "qwen" -> PromptProcessor.Template.QWEN
            "llama", "llama3" -> PromptProcessor.Template.LLAMA
            "chatml" -> PromptProcessor.Template.CHATML
            "gemma", "gemma2", "gemma3" -> PromptProcessor.Template.GEMMA
            "mistral", "mistral7b", "mistral-instruct" -> PromptProcessor.Template.MISTRAL
            "phi", "phi3", "phi3-mini", "phi4" -> PromptProcessor.Template.PHI
            "deepseek", "deepseek-v2", "deepseek-v3", "deepseek-r1" -> PromptProcessor.Template.DEEPSEEK
            "aya", "aya-expanse" -> PromptProcessor.Template.AYA
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
        PromptProcessor.Template.GEMMA -> buildGemma(messages, systemPrompt)
        PromptProcessor.Template.MISTRAL -> buildMistral(messages, systemPrompt)
        PromptProcessor.Template.PHI -> buildPhi(messages, systemPrompt)
        PromptProcessor.Template.DEEPSEEK -> buildDeepSeek(messages, systemPrompt)
        PromptProcessor.Template.AYA -> buildAya(messages, systemPrompt)
        PromptProcessor.Template.RAW -> buildRaw(messages, systemPrompt)
    }

    override fun buildParts(
        template: PromptProcessor.Template,
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): PromptProcessor.PromptParts = when (template) {
        PromptProcessor.Template.CHATML, PromptProcessor.Template.QWEN -> chatmlParts(messages, systemPrompt)
        PromptProcessor.Template.LLAMA -> llama3Parts(messages, systemPrompt)
        PromptProcessor.Template.GEMMA -> gemmaParts(messages, systemPrompt)
        PromptProcessor.Template.MISTRAL -> mistralParts(messages, systemPrompt)
        PromptProcessor.Template.PHI -> phiParts(messages, systemPrompt)
        PromptProcessor.Template.DEEPSEEK -> deepSeekParts(messages, systemPrompt)
        PromptProcessor.Template.AYA -> ayaParts(messages, systemPrompt)
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
        val attachments = messages.flatMap { it.images }
        val last = messages.lastOrNull()
        if (last != null) {
            prefix.append("<|im_start|>${last.role.tag()}\n")
            val body = last.content + "<|im_end|>\n<|im_start|>assistant\n"
            return PromptProcessor.PromptParts(prefix.toString(), body, attachments)
        }
        return PromptProcessor.PromptParts(prefix.toString(), "<|im_start|>assistant\n", attachments)
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
        val attachments = messages.flatMap { it.images }
        val last = messages.lastOrNull()
        if (last != null) {
            prefix.append("<|start_header_id|>${last.role.tag()}<|end_header_id|>\n\n")
            val body = last.content + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
            return PromptProcessor.PromptParts(prefix.toString(), body, attachments)
        }
        return PromptProcessor.PromptParts(prefix.toString(), "<|start_header_id|>assistant<|end_header_id|>\n\n", attachments)
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
        val attachments = messages.flatMap { it.images }
        val last = messages.lastOrNull()
        if (last != null) {
            prefix.append("User: ")
            return PromptProcessor.PromptParts(prefix.toString(), last.content + "\nAssistant: ", attachments)
        }
        return PromptProcessor.PromptParts(prefix.toString(), "Assistant: ", attachments)
    }

    /**
     * Gemma 2/3 (google/gemma-*). Turn roles use `<start_of_turn>` / `<end_of_turn>`, the assistant
     * role is rendered as `model`, and a leading system turn carries the system prompt.
     */
    private fun gemmaParts(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): PromptProcessor.PromptParts {
        val prefix = StringBuilder("<bos>")
        systemPrompt?.let {
            prefix.append("<start_of_turn>system\n").append(it).append("<end_of_turn>\n")
        }
        messages.dropLast(1).forEach { m ->
            prefix.append("<start_of_turn>${m.role.gemmaTag()}\n").append(m.content).append("<end_of_turn>\n")
        }
        val attachments = messages.flatMap { it.images }
        val last = messages.lastOrNull()
        if (last != null) {
            prefix.append("<start_of_turn>${last.role.gemmaTag()}\n")
            val body = last.content + "<end_of_turn>\n<start_of_turn>model\n"
            return PromptProcessor.PromptParts(prefix.toString(), body, attachments)
        }
        return PromptProcessor.PromptParts(prefix.toString(), "<start_of_turn>model\n", attachments)
    }

    /**
     * Mistral Instruct (mistralai/Mistral-7B-Instruct, Mistral-Nemo, ...). The system prompt is
     * merged into the first user turn; assistant turns end with `</s>`, and generation starts after
     * `[/INST]` with a leading space. System-role messages render as `[SYSTEM_PROMPT]` blocks.
     */
    private fun mistralParts(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): PromptProcessor.PromptParts {
        val turns = mistralMessageList(messages, systemPrompt)
        val prefix = StringBuilder()
        turns.dropLast(1).forEachIndexed { index, m ->
            if (index == 0) prefix.append("<s>")
            prefix.append(mistralTurn(m))
        }
        val attachments = messages.flatMap { it.images }
        val last = turns.lastOrNull()
        if (last != null) {
            if (turns.size == 1) prefix.append("<s>")
            if (last.role == PromptProcessor.Message.Role.USER) {
                prefix.append(mistralUserOpener())
                val body = last.content + " [/INST]"
                return PromptProcessor.PromptParts(prefix.toString(), body, attachments)
            }
            prefix.append(mistralAssistantOpener())
            val body = last.content + "</s>"
            return PromptProcessor.PromptParts(prefix.toString(), body, attachments)
        }
        return PromptProcessor.PromptParts(prefix.toString(), mistralAssistantOpener(), attachments)
    }

    /**
     * Phi-3 / Phi-4 (microsoft/Phi-3-*, Phi-4). Uniform `<|role|>\n` blocks terminated by `<|end|>`
     * with a final `<|assistant|>\n` opener.
     */
    private fun phiParts(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): PromptProcessor.PromptParts {
        val prefix = StringBuilder()
        systemPrompt?.let {
            prefix.append("<|system|>\n").append(it).append("<|end|>\n")
        }
        messages.dropLast(1).forEach { m ->
            prefix.append("<|${m.role.tag()}|>\n").append(m.content).append("<|end|>\n")
        }
        val attachments = messages.flatMap { it.images }
        val last = messages.lastOrNull()
        if (last != null) {
            prefix.append("<|${last.role.tag()}|>\n")
            val body = last.content + "<|end|>\n<|assistant|>\n"
            return PromptProcessor.PromptParts(prefix.toString(), body, attachments)
        }
        return PromptProcessor.PromptParts(prefix.toString(), "<|assistant|>\n", attachments)
    }

    /**
     * DeepSeek (deepseek-ai/DeepSeek-V2/V3/R1). `bos` + system content, then `<｜User｜>` /
     * `<｜Assistant｜>` turn markers (assistant turns end with `eos`) and a trailing
     * `<｜Assistant｜>` generation opener.
     */
    private fun deepSeekParts(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): PromptProcessor.PromptParts {
        val turns = messages.dropWhile { it.role == PromptProcessor.Message.Role.SYSTEM }
        val prefix = StringBuilder(DEEPSEEK_BOS)
        deepSeekSystem(messages, systemPrompt)?.let { prefix.append(it) }
        turns.dropLast(1).forEach { m ->
            prefix.append(deepSeekTurn(m))
        }
        val attachments = messages.flatMap { it.images }
        val last = turns.lastOrNull()
        if (last != null) {
            prefix.append(
                if (last.role == PromptProcessor.Message.Role.ASSISTANT) DEEPSEEK_ASSISTANT else DEEPSEEK_USER,
            )
            val body = last.content +
                (if (last.role == PromptProcessor.Message.Role.ASSISTANT) DEEPSEEK_EOS else "") +
                DEEPSEEK_GENERATION_OPENER
            return PromptProcessor.PromptParts(prefix.toString(), body, attachments)
        }
        return PromptProcessor.PromptParts(prefix.toString(), DEEPSEEK_GENERATION_OPENER, attachments)
    }

    /**
     * Aya Expanse / Aya-23 (CohereForAI/aya-expanse-*, CohereForAI/aya-23-*). Each turn is wrapped
     * in `<|START_OF_TURN_TOKEN|><|ROLE_TOKEN|>...<|END_OF_TURN_TOKEN|>` with a chatbot opener at the end.
     */
    private fun ayaParts(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): PromptProcessor.PromptParts {
        val prefix = StringBuilder()
        systemPrompt?.let {
            prefix.append(AYA_SYSTEM_OPEN).append(it).append(AYA_TURN_CLOSE)
        }
        messages.dropLast(1).forEach { m ->
            prefix.append(AYA_ROLE_OPENERS[m.role] ?: "").append(m.content).append(AYA_TURN_CLOSE)
        }
        val attachments = messages.flatMap { it.images }
        val last = messages.lastOrNull()
        if (last != null) {
            prefix.append(AYA_ROLE_OPENERS[last.role] ?: "")
            val body = last.content + AYA_TURN_CLOSE + AYA_CHATBOT_OPENER
            return PromptProcessor.PromptParts(prefix.toString(), body, attachments)
        }
        return PromptProcessor.PromptParts(prefix.toString(), AYA_CHATBOT_OPENER, attachments)
    }

    private fun buildGemma(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): String {
        val sb = StringBuilder("<bos>")
        systemPrompt?.let {
            sb.append("<start_of_turn>system\n").append(it).append("<end_of_turn>\n")
        }
        for (m in messages) {
            sb.append("<start_of_turn>${m.role.gemmaTag()}\n").append(m.content).append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun buildMistral(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): String {
        val sb = StringBuilder()
        val turns = mistralMessageList(messages, systemPrompt)
        turns.forEachIndexed { index, m ->
            if (index == 0) sb.append("<s>")
            sb.append(mistralTurn(m))
        }
        return sb.toString()
    }

    private fun buildPhi(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): String {
        val sb = StringBuilder()
        systemPrompt?.let {
            sb.append("<|system|>\n").append(it).append("<|end|>\n")
        }
        for (m in messages) {
            sb.append("<|${m.role.tag()}|>\n").append(m.content).append("<|end|>\n")
        }
        sb.append("<|assistant|>\n")
        return sb.toString()
    }

    private fun buildDeepSeek(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): String {
        val sb = StringBuilder(DEEPSEEK_BOS)
        deepSeekSystem(messages, systemPrompt)?.let { sb.append(it) }
        messages.dropWhile { it.role == PromptProcessor.Message.Role.SYSTEM }.forEach { m ->
            sb.append(deepSeekTurn(m))
        }
        sb.append(DEEPSEEK_GENERATION_OPENER)
        return sb.toString()
    }

    private fun buildAya(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): String {
        val sb = StringBuilder()
        systemPrompt?.let {
            sb.append(AYA_SYSTEM_OPEN).append(it).append(AYA_TURN_CLOSE)
        }
        for (m in messages) {
            sb.append(AYA_ROLE_OPENERS[m.role] ?: "").append(m.content).append(AYA_TURN_CLOSE)
        }
        sb.append(AYA_CHATBOT_OPENER)
        return sb.toString()
    }

    /** Messages with the system prompt merged into the first user turn (Mistral convention). */
    private fun mistralMessageList(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): List<PromptProcessor.Message> {
        val list = ArrayList<PromptProcessor.Message>()
        var sys = systemPrompt
        val body = if (messages.firstOrNull()?.role == PromptProcessor.Message.Role.SYSTEM) {
            val system = messages.first().content
            sys = if (sys != null) "$sys\n\n$system" else system
            messages.drop(1)
        } else {
            messages
        }
        val first = body.firstOrNull()
        if (first != null && first.role == PromptProcessor.Message.Role.USER && sys != null) {
            list.add(first.copy(content = "$sys\n\n${first.content}"))
            list.addAll(body.drop(1))
        } else if (sys != null) {
            list.add(PromptProcessor.Message(PromptProcessor.Message.Role.USER, sys))
            list.addAll(body)
        } else {
            list.addAll(body)
        }
        return list
    }

    private fun mistralTurn(m: PromptProcessor.Message): String = when (m.role) {
        PromptProcessor.Message.Role.USER -> mistralUserOpener() + m.content + " [/INST]"
        PromptProcessor.Message.Role.ASSISTANT -> mistralAssistantOpener() + m.content + "</s>"
        PromptProcessor.Message.Role.SYSTEM -> "[SYSTEM_PROMPT] " + m.content + "[/SYSTEM_PROMPT]"
    }

    private fun mistralUserOpener(): String = " [INST] "

    private fun mistralAssistantOpener(): String = " "

    /** System prompt (param + any leading system-role messages) joined for the DeepSeek header. */
    private fun deepSeekSystem(
        messages: List<PromptProcessor.Message>,
        systemPrompt: String?,
    ): String? {
        val systemContents = buildList {
            systemPrompt?.let { add(it) }
            messages.takeWhile { it.role == PromptProcessor.Message.Role.SYSTEM }.forEach { add(it.content) }
        }
        return systemContents.joinToString("\n\n").takeIf { it.isNotEmpty() }
    }

    private fun deepSeekTurn(m: PromptProcessor.Message): String = when (m.role) {
        PromptProcessor.Message.Role.USER -> DEEPSEEK_USER + m.content
        PromptProcessor.Message.Role.ASSISTANT -> DEEPSEEK_ASSISTANT + m.content + DEEPSEEK_EOS
        PromptProcessor.Message.Role.SYSTEM -> m.content
    }

    private fun PromptProcessor.Message.Role.gemmaTag(): String = when (this) {
        PromptProcessor.Message.Role.SYSTEM -> "system"
        PromptProcessor.Message.Role.USER -> "user"
        PromptProcessor.Message.Role.ASSISTANT -> "model"
    }

    private companion object {
        const val DEEPSEEK_BOS = "<｜begin▁of▁sentence｜>"
        const val DEEPSEEK_EOS = "<｜end▁of▁sentence｜>"
        const val DEEPSEEK_USER = "<｜User｜>"
        const val DEEPSEEK_ASSISTANT = "<｜Assistant｜>"
        const val DEEPSEEK_GENERATION_OPENER = "<｜Assistant｜>"
        const val AYA_SYSTEM_OPEN = "<|START_OF_TURN_TOKEN|><|SYSTEM_TOKEN|>"
        const val AYA_TURN_CLOSE = "<|END_OF_TURN_TOKEN|>"
        const val AYA_CHATBOT_OPENER = "<|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>"
        val AYA_ROLE_OPENERS = mapOf(
            PromptProcessor.Message.Role.SYSTEM to AYA_SYSTEM_OPEN,
            PromptProcessor.Message.Role.USER to "<|START_OF_TURN_TOKEN|><|USER_TOKEN|>",
            PromptProcessor.Message.Role.ASSISTANT to "<|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>",
        )
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
