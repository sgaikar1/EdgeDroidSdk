package com.sgaikar1.edgedroid.server

import com.sgaikar1.edgedroid.common.GenerationOptions
import com.sgaikar1.edgedroid.core.PromptProcessor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Pure OpenAI-wire-protocol mapping for the local server. Everything here is string-in/string-out
 * so it can be unit-tested on the JVM without an Android runtime or a model loaded.
 */
internal object OpenAiProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------ requests

    /** A single parsed `chat` message. [images] only carries on the *last* user turn. */
    data class ChatMessageInput(
        val role: String,
        val text: String,
        val images: List<PromptProcessor.PromptAttachment> = emptyList(),
    )

    /** A fully mapped `POST /v1/chat/completions` request, ready to drive the SDK. */
    data class ChatRequest(
        /** `model` echoed back from the request (the server always runs the one loaded model). */
        val model: String?,
        /** System prompt to seed the session with (null = keep the SDK's configured prompt). */
        val systemPrompt: String?,
        /** Prior turns (role, text) to seed the session with — everything before the last user turn. */
        val seedTurns: List<Pair<String, String>>,
        /** The final user turn that is actually generated. */
        val lastUser: ChatMessageInput,
        val stream: Boolean,
        val options: GenerationOptions,
    )

    fun parseChatRequest(body: String): ChatRequest {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IllegalArgumentException("request body is not valid JSON") }

        val model = root["model"]?.jsonPrimitive?.contentOrNull
        val stream = root["stream"]?.jsonPrimitive?.booleanOrNull ?: false
        val n = root["n"]?.jsonPrimitive?.intOrNull ?: 1
        if (n != 1) {
            throw IllegalArgumentException("n=$n is not supported — EdgeDroid runs one completion at a time")
        }

        val messages = root["messages"]?.jsonArray
            ?: throw IllegalArgumentException("missing required field 'messages'")

        var systemPrompt: String? = null
        val seedTurns = mutableListOf<Pair<String, String>>()
        var pendingUser: ChatMessageInput? = null

        for (raw in messages) {
            val message = raw.jsonObject
            val role = message["role"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: continue
            val content = message["content"] ?: continue
            val (text, images) = parseContent(content)
            when (role) {
                "system" -> if (systemPrompt == null) systemPrompt = text
                "user" -> {
                    // Flush any earlier pending user turn into history, then hold this one:
                    // only the LAST user message is generated.
                    pendingUser?.let {
                        seedTurns.add("user" to it.text)
                        pendingUser = null
                    }
                    pendingUser = ChatMessageInput(role, text, images)
                }
                "assistant" -> {
                    pendingUser?.let {
                        seedTurns.add("user" to it.text)
                        pendingUser = null
                    }
                    seedTurns.add("assistant" to text)
                }
                // "tool" / "function" / "developer" messages are ignored — EdgeDroid renders
                // system/user/assistant only.
                else -> Unit
            }
        }

        val lastUser = pendingUser
            ?: throw IllegalArgumentException("at least one user message is required")

        val options = GenerationOptions(
            temperature = root["temperature"]?.jsonPrimitive?.floatOrNull
                ?: GenerationOptions.DEFAULT.temperature,
            topP = root["top_p"]?.jsonPrimitive?.floatOrNull
                ?: GenerationOptions.DEFAULT.topP,
            topK = root["top_k"]?.jsonPrimitive?.intOrNull
                ?: GenerationOptions.DEFAULT.topK,
            maxTokens = (root["max_tokens"]?.jsonPrimitive?.intOrNull
                ?: root["max_completion_tokens"]?.jsonPrimitive?.intOrNull
                ?: GenerationOptions.DEFAULT.maxTokens).coerceAtLeast(1),
            stopSequences = parseStop(root["stop"]),
        )

        return ChatRequest(
            model = model,
            systemPrompt = systemPrompt,
            seedTurns = seedTurns,
            lastUser = lastUser,
            stream = stream,
            options = options,
        )
    }

    data class EmbeddingsRequest(val model: String?, val inputs: List<String>)

    fun parseEmbeddingsRequest(body: String): EmbeddingsRequest {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IllegalArgumentException("request body is not valid JSON") }
        val model = root["model"]?.jsonPrimitive?.contentOrNull
        val input = root["input"]
            ?: throw IllegalArgumentException("missing required field 'input'")
        val inputs = when (input) {
            is JsonPrimitive -> listOf(input.contentOrNull ?: "")
            is JsonArray -> input.map { part ->
                when (part) {
                    is JsonPrimitive -> part.contentOrNull ?: ""
                    else -> throw IllegalArgumentException("'input' entries must be strings")
                }
            }
            else -> throw IllegalArgumentException("'input' must be a string or an array of strings")
        }
        return EmbeddingsRequest(model, inputs)
    }

    // ------------------------------------------------------------------ responses

    fun modelsResponse(modelIds: List<String>, created: Long = now()): String = buildJsonObject {
        put("object", "list")
        put("data", buildJsonArray {
            modelIds.forEach { id ->
                add(
                    buildJsonObject {
                        put("id", id)
                        put("object", "model")
                        put("created", created)
                        put("owned_by", "edgedroid")
                    },
                )
            }
        })
    }.toString()

    fun chatResponse(request: ChatRequest, text: String, created: Long = now(), id: String = newId()): String =
        buildJsonObject {
            put("id", id)
            put("object", "chat.completion")
            put("created", created)
            put("model", request.model.orEmpty())
            put("choices", buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        put("message", buildJsonObject {
                            put("role", "assistant")
                            put("content", text)
                        })
                        put("finish_reason", "stop")
                    },
                )
            })
            put("usage", buildJsonObject {
                val promptTokens = estimateTokens(
                    request.seedTurns.sumOf { it.second.length } + request.lastUser.text.length,
                )
                val completionTokens = estimateTokens(text.length)
                put("prompt_tokens", promptTokens)
                put("completion_tokens", completionTokens)
                put("total_tokens", promptTokens + completionTokens)
            })
        }.toString()

    /** One SSE `data:` line for a streamed token. */
    fun chatChunk(request: ChatRequest, delta: String, created: Long = now(), id: String = newId()): String =
        "data: " + buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", request.model.orEmpty())
            put("choices", buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject { put("content", delta) })
                        put("finish_reason", JsonNull)
                    },
                )
            })
        }.toString() + "\n\n"

    /** Final SSE chunk signalling the stop reason. */
    fun chatFinishChunk(request: ChatRequest, created: Long = now(), id: String = newId()): String =
        "data: " + buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", request.model.orEmpty())
            put("choices", buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject {})
                        put("finish_reason", "stop")
                    },
                )
            })
        }.toString() + "\n\n"

    fun doneMarker(): String = "data: [DONE]\n\n"

    fun embeddingsResponse(request: EmbeddingsRequest, vectors: List<FloatArray>, created: Long = now()): String =
        buildJsonObject {
            put("object", "list")
            put("data", buildJsonArray {
                vectors.forEachIndexed { index, vector ->
                    add(
                        buildJsonObject {
                            put("object", "embedding")
                            put("index", index)
                            put("embedding", buildJsonArray {
                                vector.forEach { add(JsonPrimitive(it.toDouble())) }
                            })
                        },
                    )
                }
            })
            put("model", request.model.orEmpty())
            put("usage", buildJsonObject {
                val promptTokens = estimateTokens(request.inputs.sumOf { it.length })
                put("prompt_tokens", promptTokens)
                put("total_tokens", promptTokens)
            })
        }.toString()

    fun errorResponse(message: String): String = buildJsonObject {
        put("error", buildJsonObject {
            put("message", message)
            put("type", "invalid_request_error")
            put("code", JsonNull)
        })
    }.toString()

    // ------------------------------------------------------------------ helpers

    /** OpenAI `content` is either a plain string or an array of typed parts. */
    private fun parseContent(content: JsonElement): Pair<String, List<PromptProcessor.PromptAttachment>> {
        if (content is JsonPrimitive) return (content.contentOrNull ?: "") to emptyList()
        if (content !is JsonArray) return "" to emptyList()
        val text = StringBuilder()
        val images = mutableListOf<PromptProcessor.PromptAttachment>()
        for (part in content) {
            val obj = part.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> text.append(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
                "image_url" -> {
                    val url = obj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                    decodeDataUrl(url)?.let { images.add(it) }
                }
            }
        }
        return text.toString() to images
    }

    /** `data:<mime>;base64,<bytes>` → attachment. Remote http(s) image URLs are ignored. */
    private fun decodeDataUrl(url: String?): PromptProcessor.PromptAttachment? {
        if (url == null) return null
        val prefix = "data:"
        val separator = ";base64,"
        if (!url.startsWith(prefix)) return null
        val marker = url.indexOf(separator)
        if (marker < 0) return null
        val mime = url.substring(prefix.length, marker)
        val base64 = url.substring(marker + separator.length)
        return runCatching {
            PromptProcessor.PromptAttachment(Base64.getDecoder().decode(base64), mime)
        }.getOrNull()
    }

    private fun parseStop(stop: JsonElement?): List<String> = when (stop) {
        is JsonPrimitive -> listOfNotNull(stop.contentOrNull)
        is JsonArray -> stop.mapNotNull { it.jsonPrimitive.contentOrNull }
        else -> emptyList()
    }

    /** Rough token estimate (chars / 4) — the SDK has no tokenizer access from here. */
    private fun estimateTokens(charCount: Int): Int = (charCount / 4).coerceAtLeast(1)

    private fun now(): Long = System.currentTimeMillis() / 1000

    private fun newId(): String = "chatcmpl-${System.currentTimeMillis()}"
}