package com.sgaikar1.edgedroid.common

/**
 * A single emitted token of a generation stream. Runtimes report plain text pieces so the
 * SDK (and app) never care about the underlying tokenizer.
 *
 * [metrics] is an additive, optional per-stream performance snapshot (rolling tok/s, TTFT,
 * stream average) captured when this token was emitted. It is `null` for tokens produced by
 * runtimes or callers that do not track timing.
 */
data class Token(
    val index: Long,
    val id: Long,
    val text: String,
    val metrics: TokenMetrics? = null,
)
