package com.sgaikar1.edgedroid.core

import com.sgaikar1.edgedroid.common.TokenMetrics

/**
 * Tracks per-stream generation metrics (rolling tok/s, stream average and TTFT) from token
 * emission timestamps.
 *
 * The time source is injectable so unit tests can drive deterministic values; production code
 * uses [System.nanoTime] (monotonic, unaffected by wall-clock changes).
 *
 * Not thread-safe: call [onToken] from the single thread that emits tokens for one stream.
 */
class StreamMetricsTracker(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val startAt: Long = nanoTime()
    private var firstTokenAt: Long? = null
    private var lastTokenAt: Long = startAt

    /** Number of tokens emitted so far (i.e. the count this tracker has accounted for). */
    var emittedTokens: Long = 0L
        private set

    /**
     * Account for one emitted token and return its [TokenMetrics] snapshot.
     *
     * [TokenMetrics.tokensPerSecond] reflects the time since the previous emission (for the
     * first token, since the stream started); [TokenMetrics.averageTokensPerSecond] is the
     * stream average including this token; [TokenMetrics.timeToFirstTokenMs] is stable across
     * all tokens of the stream.
     */
    fun onToken(): TokenMetrics {
        val now = nanoTime()
        val firstAt = firstTokenAt ?: now.also { firstTokenAt = it }

        val timeToFirstTokenMs = ((firstAt - startAt) / 1_000_000L).coerceAtLeast(0L)
        val sinceLastSec = (now - lastTokenAt) / 1_000_000_000.0
        val rolling = if (sinceLastSec > 0.0) 1.0 / sinceLastSec else 0.0

        val total = emittedTokens + 1L
        val elapsedSec = (now - startAt) / 1_000_000_000.0
        val average = if (elapsedSec > 0.0) total / elapsedSec else 0.0

        lastTokenAt = now
        emittedTokens = total

        return TokenMetrics(
            tokensPerSecond = rolling,
            averageTokensPerSecond = average,
            timeToFirstTokenMs = timeToFirstTokenMs,
        )
    }
}