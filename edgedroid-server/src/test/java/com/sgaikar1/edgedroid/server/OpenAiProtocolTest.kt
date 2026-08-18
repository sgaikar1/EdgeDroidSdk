package com.sgaikar1.edgedroid.server

import com.sgaikar1.edgedroid.common.GenerationOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------ chat requests

    @Test
    fun `parse minimal chat request`() {
        val body = """
            {"model":"my-model","messages":[{"role":"user","content":"Hello"}]}
        """.trimIndent()
        val request = OpenAiProtocol.parseChatRequest(body)

        assertEquals("my-model", request.model)
        assertEquals("Hello", request.lastUser.text)
        assertTrue(request.seedTurns.isEmpty())
        assertNull(request.systemPrompt)
        assertEquals(false, request.stream)
    }

    @Test
    fun `parse request with system prompt and history`() {
        val body = """
            {"messages":[
                {"role":"system","content":"You are helpful"},
                {"role":"user","content":"What is 2+2?"},
                {"role":"assistant","content":"4"},
                {"role":"user","content":"And 3+3?"}
            ]}
        """.trimIndent()
        val request = OpenAiProtocol.parseChatRequest(body)

        assertEquals("You are helpful", request.systemPrompt)
        assertEquals(listOf("user" to "What is 2+2?", "assistant" to "4"), request.seedTurns)
        assertEquals("And 3+3?", request.lastUser.text)
    }

    @Test
    fun `maps sampling options`() {
        val body = """
            {"messages":[{"role":"user","content":"hi"}],
             "temperature":0.3,"top_p":0.6,"top_k":25,"max_tokens":128,"stop":["END","STOP"]}
        """.trimIndent()
        val request = OpenAiProtocol.parseChatRequest(body)
        val options = request.options

        assertEquals(0.3f, options.temperature, 0.0001f)
        assertEquals(0.6f, options.topP, 0.0001f)
        assertEquals(25, options.topK)
        assertEquals(128, options.maxTokens)
        assertEquals(listOf("END", "STOP"), options.stopSequences)
    }

    @Test
    fun `uses SDK defaults when sampling fields are absent`() {
        val request = OpenAiProtocol.parseChatRequest(
            """{"messages":[{"role":"user","content":"hi"}]}""",
        )
        val options = request.options
        assertEquals(GenerationOptions.DEFAULT.temperature, options.temperature, 0.0001f)
        assertEquals(GenerationOptions.DEFAULT.topP, options.topP, 0.0001f)
        assertEquals(GenerationOptions.DEFAULT.topK, options.topK)
        assertEquals(GenerationOptions.DEFAULT.maxTokens, options.maxTokens)
    }

    @Test
    fun `supports max_completion_tokens alias`() {
        val request = OpenAiProtocol.parseChatRequest(
            """{"messages":[{"role":"user","content":"hi"}],"max_completion_tokens":64}""",
        )
        assertEquals(64, request.options.maxTokens)
    }

    @Test
    fun `stream flag`() {
        val request = OpenAiProtocol.parseChatRequest(
            """{"messages":[{"role":"user","content":"hi"}],"stream":true}""",
        )
        assertEquals(true, request.stream)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects n greater than one`() {
        OpenAiProtocol.parseChatRequest(
            """{"messages":[{"role":"user","content":"hi"}],"n":2}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects missing messages`() {
        OpenAiProtocol.parseChatRequest("""{"model":"x"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non json body`() {
        OpenAiProtocol.parseChatRequest("not json")
    }

    @Test
    fun `parses content parts with a base64 image`() {
        val tinyPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val body = """
            {"messages":[{"role":"user","content":[
                {"type":"text","text":"Describe this"},
                {"type":"image_url","image_url":{"url":"data:image/png;base64,$tinyPng"}}
            ]}]}
        """.trimIndent()
        val request = OpenAiProtocol.parseChatRequest(body)

        assertEquals("Describe this", request.lastUser.text)
        assertEquals(1, request.lastUser.images.size)
        assertEquals("image/png", request.lastUser.images[0].mimeType)
        assertTrue(request.lastUser.images[0].bytes.isNotEmpty())
    }

    @Test
    fun `ignores remote image urls in content parts`() {
        val body = """
            {"messages":[{"role":"user","content":[
                {"type":"text","text":"hi"},
                {"type":"image_url","image_url":{"url":"https://example.com/x.png"}}
            ]}]}
        """.trimIndent()
        val request = OpenAiProtocol.parseChatRequest(body)
        assertEquals("hi", request.lastUser.text)
        assertTrue(request.lastUser.images.isEmpty())
    }

    @Test
    fun `ignores tool and unknown roles`() {
        val body = """
            {"messages":[
                {"role":"system","content":"s"},
                {"role":"user","content":"u1"},
                {"role":"tool","tool_call_id":"t1","content":"tool result"},
                {"role":"user","content":"u2"}
            ]}
        """.trimIndent()
        val request = OpenAiProtocol.parseChatRequest(body)
        assertEquals("s", request.systemPrompt)
        assertEquals(listOf("user" to "u1"), request.seedTurns)
        assertEquals("u2", request.lastUser.text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a request with no user message`() {
        OpenAiProtocol.parseChatRequest(
            """{"messages":[{"role":"assistant","content":"hi"}]}""",
        )
    }

    // ------------------------------------------------------------------ embeddings requests

    @Test
    fun `parses embeddings request with string input`() {
        val request = OpenAiProtocol.parseEmbeddingsRequest(
            """{"model":"mini","input":"hello world"}""",
        )
        assertEquals("mini", request.model)
        assertEquals(listOf("hello world"), request.inputs)
    }

    @Test
    fun `parses embeddings request with array input`() {
        val request = OpenAiProtocol.parseEmbeddingsRequest(
            """{"model":"mini","input":["a","b"]}""",
        )
        assertEquals(listOf("a", "b"), request.inputs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects embeddings request without input`() {
        OpenAiProtocol.parseEmbeddingsRequest("""{"model":"mini"}""")
    }

    // ------------------------------------------------------------------ responses

    @Test
    fun `models response is a valid openai list`() {
        val raw = OpenAiProtocol.modelsResponse(listOf("a", "b"), created = 1L)
        val root = json.parseToJsonElement(raw).jsonObject
        assertEquals("list", root["object"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, root["data"]?.jsonArray?.size)
        assertEquals("a", root["data"]?.jsonArray?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `chat response shape matches openai`() {
        val request = OpenAiProtocol.parseChatRequest(
            """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
        )
        val raw = OpenAiProtocol.chatResponse(request, "hello there", created = 1L, id = "chatcmpl-1")
        val root = json.parseToJsonElement(raw).jsonObject

        assertEquals("chat.completion", root["object"]?.jsonPrimitive?.contentOrNull)
        assertEquals("chatcmpl-1", root["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1L, root["created"]?.jsonPrimitive?.contentOrNull?.toLong())
        assertEquals("m", root["model"]?.jsonPrimitive?.contentOrNull)

        val choice = root["choices"]?.jsonArray?.get(0)?.jsonObject
        assertEquals("assistant", choice?.get("message")?.jsonObject?.get("role")?.jsonPrimitive?.contentOrNull)
        assertEquals("hello there", choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull)
        assertEquals("stop", choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull)
        assertTrue(root.containsKey("usage"))
    }

    @Test
    fun `streaming chunk is sse formatted`() {
        val request = OpenAiProtocol.parseChatRequest(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"stream":true}""",
        )
        val chunk = OpenAiProtocol.chatChunk(request, "tok", created = 1L, id = "id-1")
        assertTrue(chunk.startsWith("data: "))
        assertTrue(chunk.endsWith("\n\n"))
        val payload = chunk.removePrefix("data: ").removeSuffix("\n\n")
        val root = json.parseToJsonElement(payload).jsonObject
        assertEquals("chat.completion.chunk", root["object"]?.jsonPrimitive?.contentOrNull)
        val delta = root["choices"]?.jsonArray?.get(0)?.jsonObject?.get("delta")?.jsonObject
        assertEquals("tok", delta?.get("content")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `finish chunk and done marker`() {
        val request = OpenAiProtocol.parseChatRequest(
            """{"messages":[{"role":"user","content":"hi"}]}""",
        )
        val finish = OpenAiProtocol.chatFinishChunk(request, created = 1L, id = "id-1")
        assertTrue(finish.startsWith("data: "))
        val payload = finish.removePrefix("data: ").removeSuffix("\n\n")
        val choice = json.parseToJsonElement(payload).jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject
        assertEquals("stop", choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull)
        assertEquals("data: [DONE]\n\n", OpenAiProtocol.doneMarker())
    }

    @Test
    fun `embeddings response shape`() {
        val request = OpenAiProtocol.parseEmbeddingsRequest("""{"model":"mini","input":"x"}""")
        val raw = OpenAiProtocol.embeddingsResponse(request, listOf(floatArrayOf(0.1f, 0.2f)), created = 1L)
        val root = json.parseToJsonElement(raw).jsonObject
        assertEquals("list", root["object"]?.jsonPrimitive?.contentOrNull)
        val data = root["data"]?.jsonArray
        assertEquals(1, data?.size)
        val embedding = data?.get(0)?.jsonObject?.get("embedding")?.jsonArray
        assertEquals(2, embedding?.size)
    }

    @Test
    fun `error response shape`() {
        val raw = OpenAiProtocol.errorResponse("boom")
        val root = json.parseToJsonElement(raw).jsonObject
        val error = root["error"]?.jsonObject
        assertEquals("boom", error?.get("message")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `embeddings usage present`() {
        val request = OpenAiProtocol.parseEmbeddingsRequest("""{"model":"mini","input":"hello"}""")
        val raw = OpenAiProtocol.embeddingsResponse(request, listOf(floatArrayOf(0.5f)), created = 1L)
        val usage = json.parseToJsonElement(raw).jsonObject["usage"]?.jsonObject
        assertTrue(usage?.containsKey("prompt_tokens") == true)
        assertTrue(usage?.containsKey("total_tokens") == true)
    }
}