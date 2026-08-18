package com.sgaikar1.edgedroid.server

import com.sgaikar1.edgedroid.api.EdgeDroid
import com.sgaikar1.edgedroid.common.LogProvider
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An OpenAI-compatible HTTP server that backs onto a single [EdgeDroid] instance. It exposes
 * `GET /v1/models`, `POST /v1/chat/completions` (streaming **and** non-streaming) and
 * `POST /v1/embeddings` (only when the loaded runtime supports embeddings) over loopback.
 *
 * ## Security
 * The server binds **only** to `127.0.0.1` — it is unreachable from other devices or the
 * network. It accepts unauthenticated requests from any local process, so treat it as a
 * development/demo tool: do not expose it beyond the device (e.g. `adb reverse` / port
 * forwarding only on a device you control), and never bind it to a routable interface.
 *
 * ## Lifecycle
 * Call [start] when the app comes to the foreground and [stop] when it leaves the foreground
 * (or on process teardown). The server serializes generation — EdgeDroid runs one model
 * handle, so concurrent completions queue behind a mutex.
 *
 * ## Placement
 * This module is intentionally separate from the sample UI so any app can embed it, and the
 * wire protocol stays unit-testable on the JVM (see [OpenAiProtocol]).
 */
class EdgeDroidOpenAiServer(
    private val sdk: EdgeDroid,
    port: Int = DEFAULT_PORT,
    private val defaultModelId: String? = null,
    private val log: LogProvider = LogProvider.NO_OP,
) : NanoHTTPD("127.0.0.1", port.coerceIn(1, 65535)) {

    val port: Int = port.coerceIn(1, 65535)

    @Volatile
    private var running = false

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generationMutex = Mutex()
    private val activePipes = CopyOnWriteArraySet<PipedOutputStream>()

    /** Loopback URL clients should point at, e.g. `http://127.0.0.1:8080`. */
    val baseUrl: String get() = "http://127.0.0.1:$port"

    val isRunning: Boolean get() = running

    /** Bind the loopback socket and start accepting requests. Safe to call once per instance. */
    override fun start() {
        synchronized(lock) {
            if (running) return
            setAsyncRunner(AsyncRunner())
            super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            running = true
            log.log(LogProvider.Level.INFO, TAG, "OpenAI-compatible server listening on $baseUrl")
        }
    }

    /** Stop accepting requests and abort any in-flight generations. */
    override fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            // Closing the pipes makes in-flight stream writes throw, ending their coroutines.
            activePipes.forEach { pipe -> runCatching { pipe.close() } }
            activePipes.clear()
            super.stop()
            log.log(LogProvider.Level.INFO, TAG, "OpenAI-compatible server stopped")
        }
    }

    // ------------------------------------------------------------------ endpoints

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) return withCors(emptyJson(Response.Status.OK))
        val uri = session.uri
        return when {
            uri == "/v1/models" && session.method == Method.GET -> handleModels()
            uri == "/v1/chat/completions" && session.method == Method.POST -> handleChat(session)
            uri == "/v1/embeddings" && session.method == Method.POST -> handleEmbeddings(session)
            uri == "/v1/models" || uri == "/v1/chat/completions" || uri == "/v1/embeddings" ->
                jsonError(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
            else -> jsonError(Response.Status.NOT_FOUND, "no such endpoint: $uri")
        }
    }

    private fun handleModels(): Response {
        val ids = sdk.models.available().map { it.id }
            .ifEmpty { listOf(defaultModelId ?: FALLBACK_MODEL_ID) }
        return json(Response.Status.OK, OpenAiProtocol.modelsResponse(ids))
    }

    private fun handleChat(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = try {
            OpenAiProtocol.parseChatRequest(body)
        } catch (e: IllegalArgumentException) {
            return jsonError(Response.Status.BAD_REQUEST, e.message ?: "bad request")
        }
        return if (request.stream) streamChat(request) else nonStreamChat(request)
    }

    private fun nonStreamChat(request: OpenAiProtocol.ChatRequest): Response = try {
        val text = runBlocking {
            generationMutex.withLock {
                seedSession(request)
                sdk.generate(
                    prompt = request.lastUser.text,
                    images = request.lastUser.images,
                    options = request.options,
                )
            }
        }
        json(Response.Status.OK, OpenAiProtocol.chatResponse(request, text))
    } catch (t: Throwable) {
        serverError("chat completion failed", t)
    }

    private fun streamChat(request: OpenAiProtocol.ChatRequest): Response {
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, STREAM_BUFFER_BYTES)
        activePipes.add(pipeOut)
        scope.launch {
            try {
                generationMutex.withLock {
                    seedSession(request)
                    sdk.stream(
                        prompt = request.lastUser.text,
                        images = request.lastUser.images,
                        options = request.options,
                    ) { token ->
                        val event = OpenAiProtocol.chatChunk(request, token.text, id = STREAM_ID)
                        pipeOut.write(event.toByteArray(Charsets.UTF_8))
                        pipeOut.flush()
                    }
                    pipeOut.write(OpenAiProtocol.chatFinishChunk(request, id = STREAM_ID).toByteArray(Charsets.UTF_8))
                    pipeOut.write(OpenAiProtocol.doneMarker().toByteArray(Charsets.UTF_8))
                    pipeOut.flush()
                }
            } catch (t: Throwable) {
                // Client disconnect (pipe closed) or generation error; nothing more to stream.
                log.log(LogProvider.Level.DEBUG, TAG, "stream ended: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                runCatching { pipeOut.close() }
                activePipes.remove(pipeOut)
            }
        }
        val response = NanoHTTPD.newChunkedResponse(Response.Status.OK, "text/event-stream", pipeIn)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("X-Accel-Buffering", "no")
        return withCors(response)
    }

    private fun handleEmbeddings(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = try {
            OpenAiProtocol.parseEmbeddingsRequest(body)
        } catch (e: IllegalArgumentException) {
            return jsonError(Response.Status.BAD_REQUEST, e.message ?: "bad request")
        }
        return try {
            val vectors = runBlocking {
                generationMutex.withLock {
                    request.inputs.map { sdk.embeddings(it) }
                }
            }
            json(Response.Status.OK, OpenAiProtocol.embeddingsResponse(request, vectors))
        } catch (e: UnsupportedOperationException) {
            jsonError(
                Response.Status.NOT_IMPLEMENTED,
                "embeddings are not supported by the currently loaded runtime",
            )
        } catch (t: Throwable) {
            serverError("embeddings failed", t)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Seed the SDK session from the request; [EdgeDroid.stream]/[EdgeDroid.generate] add the final user turn. */
    private fun seedSession(request: OpenAiProtocol.ChatRequest) {
        sdk.seedChat(
            systemPrompt = request.systemPrompt ?: sdk.systemPrompt,
            messages = request.seedTurns,
        )
    }

    private fun readBody(session: IHTTPSession): String =
        session.inputStream.readBytes().toString(Charsets.UTF_8)

    private fun json(status: Response.Status, body: String): Response {
        val response = NanoHTTPD.newFixedLengthResponse(status, "application/json", body)
        return withCors(response)
    }

    private fun jsonError(status: Response.Status, message: String): Response =
        json(status, OpenAiProtocol.errorResponse(message))

    private fun emptyJson(status: Response.Status): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", "{}")

    private fun serverError(tag: String, t: Throwable): Response {
        log.log(LogProvider.Level.ERROR, TAG, "$tag: ${t.message}", t)
        return jsonError(Response.Status.INTERNAL_ERROR, "$tag: ${t.message ?: t.javaClass.simpleName}")
    }

    private fun withCors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }

    /** Bounded HTTP worker pool; generation itself is serialized by [generationMutex]. */
    private class AsyncRunner : NanoHTTPD.AsyncRunner {
        private val executor = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "edgedroid-server-http").apply { isDaemon = true }
        }

        override fun closeAll() {
            executor.shutdownNow()
        }

        override fun closed(clientHandler: NanoHTTPD.ClientHandler) = Unit

        override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
            executor.execute { clientHandler.run() }
        }
    }

    companion object {
        private const val TAG = "EdgeDroid.OpenAiServer"
        private const val DEFAULT_PORT = 8080
        private const val FALLBACK_MODEL_ID = "edgedroid-local"
        private const val STREAM_BUFFER_BYTES = 64 * 1024

        /** Stable id reused across all chunks of one streaming completion. */
        private val STREAM_ID = "chatcmpl-local"
    }
}