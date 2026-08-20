package com.sgaikar1.edgedroid.server

import com.sgaikar1.edgedroid.api.EdgeDroid
import com.sgaikar1.edgedroid.common.LogProvider
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
 * ## Streaming liveness
 * SSE responses never compress (see [useGzipWhenAccepted]) and each stream is fed through
 * [StreamPipe], a pipe bounded in **bytes**: a client that stops reading for
 * [STREAM_WRITE_TIMEOUT_MS] causes the generation to abort so the generation mutex is
 * released and other endpoints stay responsive.
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
    private val activePipes = CopyOnWriteArraySet<StreamPipe>()

    /** Loopback URL clients should point at, e.g. `http://127.0.0.1:8080`. */
    val baseUrl: String get() = "http://127.0.0.1:$port"

    val isRunning: Boolean get() = running

    /**
     * Never gzip responses. NanoHTTPD gzip-compresses any `text/...` response (including
     * `text/event-stream`) when the client sends `Accept-Encoding: gzip` — which the popular
     * OpenAI SDKs and browsers all do — and its GZIPOutputStream is not sync-flushed, so small
     * per-token writes would be buffered into lumps and effectively break SSE streaming.
     * Localhost payloads are tiny, so compression buys nothing anyway.
     */
    override fun useGzipWhenAccepted(r: NanoHTTPD.Response): Boolean = false

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
            // Aborting the pipes unblocks their producers, ending in-flight generations.
            activePipes.forEach { pipe -> pipe.abort() }
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
        val pipe = StreamPipe(STREAM_BUFFER_BYTES, STREAM_WRITE_TIMEOUT_MS)
        activePipes.add(pipe)
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
                        if (!pipe.offer(event.toByteArray(Charsets.UTF_8))) {
                            throw IOException("client stopped reading the stream")
                        }
                    }
                    // Best-effort tail; a full pipe here only means the client was already gone.
                    pipe.offerTail(OpenAiProtocol.chatFinishChunk(request, id = STREAM_ID).toByteArray(Charsets.UTF_8))
                    pipe.offerTail(OpenAiProtocol.doneMarker().toByteArray(Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                // Generation error or slow/disconnected client; nothing more to stream.
                log.log(LogProvider.Level.DEBUG, TAG, "stream ended: ${t.message ?: t.javaClass.simpleName}")
                pipe.offerTail(
                    ("data: " + OpenAiProtocol.errorResponse("stream aborted: ${t.message}") + "\n\n")
                        .toByteArray(Charsets.UTF_8),
                )
            } finally {
                pipe.finish()
                activePipes.remove(pipe)
            }
        }
        val response = NanoHTTPD.newChunkedResponse(Response.Status.OK, "text/event-stream", pipe)
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

    /** Bounded HTTP worker pool with a queue; generation itself is serialized by [generationMutex]. */
    private class AsyncRunner : NanoHTTPD.AsyncRunner {
        private val executor = ThreadPoolExecutor(
            HTTP_CORE_THREADS,
            HTTP_MAX_THREADS,
            THREAD_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
        ) { r ->
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

        /** Actual byte budget of one stream pipe (not element count). */
        private const val STREAM_BUFFER_BYTES = 64 * 1024

        /** How long a full stream pipe (client not reading) waits before the stream is aborted. */
        private const val STREAM_WRITE_TIMEOUT_MS = 30_000L

        private const val HTTP_CORE_THREADS = 4
        private const val HTTP_MAX_THREADS = 32
        private const val THREAD_KEEP_ALIVE_SECONDS = 60L

        /** Stable id reused across all chunks of one streaming completion. */
        private val STREAM_ID = "chatcmpl-local"
    }
}

/**
 * Bounded (in **bytes**), timeout-guarded byte pipe that backs an SSE response. The generation
 * coroutine [offer]s chunk-sized writes; NanoHTTPD's response sender [read]s them.
 *
 * Capacity is enforced with a [Semaphore] holding [capacityBytes] permits — acquired per byte
 * on write, released per byte once a chunk is fully read — so a paused client fills the budget
 * quickly and a subsequent [offer] fails after [writeTimeoutMs], aborting the generation and
 * releasing the generation mutex instead of wedging every endpoint.
 *
 * [abort] (server stop or socket death) releases all permits and signals EOF, so a producer
 * blocked in [offer] wakes immediately, observes the closed flag, and returns false; the
 * underlying queue is unbounded, so the EOF marker can never block behind producers.
 */
internal class StreamPipe(
    private val capacityBytes: Int,
    private val writeTimeoutMs: Long,
) : InputStream() {

    private val queue = LinkedBlockingQueue<ByteArray>()

    /** EOF sentinel — LinkedBlockingQueue forbids null elements, and SSE chunks are never empty. */
    private val EOF = ByteArray(0)

    private val permits = Semaphore(capacityBytes, true)
    @Volatile private var closed = false
    private var current: ByteArray? = null
    private var offset = 0
    private var eof = false

    /**
     * Enqueue [data], blocking up to [writeTimeoutMs] for byte budget. Returns false when the
     * pipe stayed full (client not reading) past the timeout or was aborted.
     */
    fun offer(data: ByteArray): Boolean {
        if (closed) return false
        if (!permits.tryAcquire(data.size, writeTimeoutMs, TimeUnit.MILLISECONDS)) return false
        if (closed) {
            permits.release(data.size)
            return false
        }
        queue.offer(data)
        return true
    }

    /** Best-effort write for tail/error events; never blocks the producer forever. */
    fun offerTail(data: ByteArray?) {
        if (closed) return
        if (data == null) {
            // EOF marker; the queue itself is unbounded so this never blocks.
            queue.offer(EOF)
            return
        }
        if (!permits.tryAcquire(data.size, TAIL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            // Client is gone — drop the tail and let the reader see EOF.
            queue.offer(EOF)
            return
        }
        if (closed) {
            permits.release(data.size)
            queue.offer(EOF)
            return
        }
        queue.offer(data)
    }

    /** Signal EOF to the reader; safe to call once generation finished. */
    fun finish() {
        offerTail(null)
    }

    /** Abort: unblock producers (release all byte budget) and signal EOF to the reader. */
    fun abort() {
        closed = true
        permits.release(capacityBytes)
        queue.offer(EOF)
    }

    override fun read(): Int {
        while (true) {
            val chunk = current
            if (chunk != null) {
                if (offset < chunk.size) return chunk[offset++].toInt() and 0xFF
                current = null
                offset = 0
            }
            if (eof) return -1
            val next = queue.take()
            if (next === EOF) {
                eof = true
                return -1
            }
            // A chunk's byte budget is freed as soon as the reader starts consuming it, so the
            // bound is on *queued* bytes; at most one in-flight chunk (~an SSE event) is extra.
            permits.release(next.size)
            current = next
            offset = 0
        }
    }

    override fun close() {
        // NanoHTTPD closes the body stream when the socket dies; unblock producers.
        abort()
    }

    private companion object {
        const val TAIL_TIMEOUT_MS = 5_000L
    }
}