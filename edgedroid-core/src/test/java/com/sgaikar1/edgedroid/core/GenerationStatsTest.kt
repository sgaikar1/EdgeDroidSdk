package com.sgaikar1.edgedroid.core

import com.sgaikar1.edgedroid.common.GenerationStats
import org.junit.Assert.assertEquals
import org.junit.Test

/** Aggregation semantics of the runtime-level session counters. */
class GenerationStatsTest {

    @Test
    fun `tokens per second derives from eval tokens and eval ms`() {
        val stats = GenerationStats(evalTokens = 50, evalMs = 1_000)
        assertEquals(50.0, stats.tokensPerSecond, 1e-9)
    }

    @Test
    fun `zero eval time yields zero tokens per second`() {
        val stats = GenerationStats(evalTokens = 50, evalMs = 0)
        assertEquals(0.0, stats.tokensPerSecond, 1e-9)
    }

    @Test
    fun `total ms sums prompt and eval time`() {
        val stats = GenerationStats(promptTokens = 10, evalTokens = 40, promptMs = 250, evalMs = 750)
        assertEquals(1_000L, stats.totalMs)
    }

    @Test
    fun `plus aggregates two sessions cumulatively`() {
        val first = GenerationStats(promptTokens = 10, evalTokens = 40, promptMs = 250, evalMs = 750)
        val second = GenerationStats(promptTokens = 20, evalTokens = 60, promptMs = 500, evalMs = 1_500)

        val total = first + second

        assertEquals(30L, total.promptTokens)
        assertEquals(100L, total.evalTokens)
        assertEquals(750L, total.promptMs)
        assertEquals(2_250L, total.evalMs)
        assertEquals(3_000L, total.totalMs)
        // 100 tokens / 2.25s ≈ 44.44 tok/s
        assertEquals(100.0 / 2.25, total.tokensPerSecond, 1e-9)
    }

    @Test
    fun `empty stats are neutral for aggregation`() {
        val empty = GenerationStats()
        val one = GenerationStats(evalTokens = 7, evalMs = 100)

        val total = empty + one

        assertEquals(one, total)
    }
}