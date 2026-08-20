package com.sgaikar1.edgedroid.core

import com.sgaikar1.edgedroid.common.TokenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the per-stream metrics tracker (rolling tok/s, stream average, TTFT).
 * A controllable nano-time source makes the arithmetic exact.
 */
class StreamMetricsTrackerTest {

    /** Fake monotonic clock in nanoseconds. */
    private class FakeClock {
        var nowNanos = 1_000_000_000L
        fun advanceBy(ms: Long) {
            nowNanos += ms * 1_000_000L
        }
        val source: () -> Long get() = { nowNanos }
    }

    private fun assertMetrics(
        m: TokenMetrics,
        expectedRolling: Double,
        expectedAvg: Double,
        expectedTtftMs: Long,
    ) {
        assertEquals("rolling tok/s", expectedRolling, m.tokensPerSecond, 1e-9)
        assertEquals("average tok/s", expectedAvg, m.averageTokensPerSecond, 1e-9)
        assertEquals("TTFT ms", expectedTtftMs, m.timeToFirstTokenMs)
    }

    @Test
    fun `first token captures ttft and rolling rate since stream start`() {
        val clock = FakeClock()
        val tracker = StreamMetricsTracker(clock.source)
        clock.advanceBy(200)

        val m = tracker.onToken()

        // Stream started at t=0; first token at t=200ms -> TTFT=200ms,
        // rolling = 1 token / 0.2s = 5 tok/s, average = 1 / 0.2s = 5 tok/s.
        assertMetrics(m, expectedRolling = 5.0, expectedAvg = 5.0, expectedTtftMs = 200)
        assertEquals(1L, tracker.emittedTokens)
    }

    @Test
    fun `rolling rate reflects inter-token gaps and ttft stays stable`() {
        val clock = FakeClock()
        val tracker = StreamMetricsTracker(clock.source)

        clock.advanceBy(200)
        val first = tracker.onToken() // t=200ms
        clock.advanceBy(300)
        val second = tracker.onToken() // t=500ms: gap 300ms -> 3.33 tok/s
        clock.advanceBy(500)
        val third = tracker.onToken() // t=1000ms: gap 500ms -> 2 tok/s

        assertMetrics(first, expectedRolling = 5.0, expectedAvg = 5.0, expectedTtftMs = 200)
        assertMetrics(second, expectedRolling = 1.0 / 0.3, expectedAvg = 2.0 / 0.5, expectedTtftMs = 200)
        assertMetrics(third, expectedRolling = 1.0 / 0.5, expectedAvg = 3.0 / 1.0, expectedTtftMs = 200)
        assertEquals(3L, tracker.emittedTokens)
    }

    @Test
    fun `average on the last token equals the final stream average`() {
        val clock = FakeClock()
        val tracker = StreamMetricsTracker(clock.source)

        clock.advanceBy(100)
        tracker.onToken()
        clock.advanceBy(100)
        tracker.onToken()
        clock.advanceBy(800)
        val last = tracker.onToken() // 3 tokens over 1s -> final average 3 tok/s

        assertMetrics(last, expectedRolling = 1.0 / 0.8, expectedAvg = 3.0, expectedTtftMs = 100)
        assertEquals(3.0, last.averageTokensPerSecond, 1e-9)
    }

    @Test
    fun `zero elapsed time yields zero rates instead of infinity`() {
        val clock = FakeClock()
        val tracker = StreamMetricsTracker(clock.source)

        val m = tracker.onToken() // same instant as stream start

        assertEquals(0.0, m.tokensPerSecond, 1e-9)
        assertEquals(0.0, m.averageTokensPerSecond, 1e-9)
        assertEquals(0L, m.timeToFirstTokenMs)
    }

    @Test
    fun `token metrics are additive and optional on the public Token`() {
        val plain = com.sgaikar1.edgedroid.common.Token(0, 1, "hello")
        assertTrue("metrics defaults to null for compatibility", plain.metrics == null)
        val measured = plain.copy(metrics = TokenMetrics(1.0, 1.0, 50))
        assertEquals(1.0, measured.metrics!!.tokensPerSecond, 1e-9)
        assertEquals(50L, measured.metrics!!.timeToFirstTokenMs)
    }
}