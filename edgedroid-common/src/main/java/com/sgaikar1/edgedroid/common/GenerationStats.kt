package com.sgaikar1.edgedroid.common

/**
 * Aggregate generation statistics reported by a runtime for the current session (since the
 * model was loaded). Runtimes with native timing support (e.g. llama.cpp's `llama_perf_*`)
 * fill these from their own counters; software runtimes fall back to wall-clock measurements.
 *
 * - [promptTokens] / [promptMs]: tokens (and time) spent processing the prompt before the
 *   first generated token.
 * - [evalTokens] / [evalMs]: generated ("eval") tokens and the time spent decoding them.
 */
data class GenerationStats(
    val promptTokens: Long = 0L,
    val evalTokens: Long = 0L,
    val promptMs: Long = 0L,
    val evalMs: Long = 0L,
) {
    /** Total wall time of all generation work reported by these stats. */
    val totalMs: Long
        get() = promptMs + evalMs

    /** Average decode throughput in tokens/second computed from [evalTokens] / [evalMs]. */
    val tokensPerSecond: Double
        get() = if (evalMs > 0L) evalTokens * 1000.0 / evalMs else 0.0

    /** Aggregate two snapshots (e.g. across multiple generations in one session). */
    operator fun plus(other: GenerationStats): GenerationStats = GenerationStats(
        promptTokens = promptTokens + other.promptTokens,
        evalTokens = evalTokens + other.evalTokens,
        promptMs = promptMs + other.promptMs,
        evalMs = evalMs + other.evalMs,
    )
}