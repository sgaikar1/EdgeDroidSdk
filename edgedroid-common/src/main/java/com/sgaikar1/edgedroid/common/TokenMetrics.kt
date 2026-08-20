package com.sgaikar1.edgedroid.common

/**
 * Per-stream performance metrics captured when a [Token] was emitted.
 *
 * - [tokensPerSecond] is the instantaneous ("rolling") rate measured between consecutive
 *   emissions.
 * - [averageTokensPerSecond] is the stream-wide average up to (and including) this token.
 *   On the last token of a stream it equals the final average for the whole generation.
 * - [timeToFirstTokenMs] is the latency from the start of the generation call to the first
 *   emitted token (TTFT). It is identical on every token of a stream.
 *
 * All values are best-effort measurements taken by the runtime; they are never used for
 * correctness decisions.
 */
data class TokenMetrics(
    val tokensPerSecond: Double,
    val averageTokensPerSecond: Double,
    val timeToFirstTokenMs: Long,
)