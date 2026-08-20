package com.sgaikar1.edgedroid.server

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPipeTest {

    @Test
    fun `bytes are delivered in order until finish`() {
        val pipe = StreamPipe(capacityBytes = 64, writeTimeoutMs = 100)
        assertTrue(pipe.offer("hello".toByteArray(Charsets.UTF_8)))
        assertTrue(pipe.offer(" world".toByteArray(Charsets.UTF_8)))
        pipe.finish()

        val out = ByteArrayOutputStream()
        pipe.copyTo(out)
        assertEquals("hello world", out.toString(Charsets.UTF_8))
    }

    @Test
    fun `offer fails once the byte budget is full and the reader is stalled`() {
        val pipe = StreamPipe(capacityBytes = 4, writeTimeoutMs = 50)
        assertTrue(pipe.offer("abcd".toByteArray(Charsets.UTF_8))) // fills the whole budget

        val start = System.nanoTime()
        val accepted = pipe.offer("efgh".toByteArray(Charsets.UTF_8)) // reader is not reading
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertFalse(accepted)
        assertTrue("offer should time out quickly, took ${elapsedMs}ms", elapsedMs < 1_000)
    }

    @Test
    fun `draining the reader frees byte capacity for more writes`() {
        val pipe = StreamPipe(capacityBytes = 4, writeTimeoutMs = 50)
        assertTrue(pipe.offer("abcd".toByteArray(Charsets.UTF_8)))

        // Reader is not reading yet, so the queued chunk keeps the whole budget busy.
        assertFalse(pipe.offer("efgh".toByteArray(Charsets.UTF_8)))

        // Dequeuing the chunk frees its budget even before it is fully consumed.
        assertEquals('a'.code, pipe.read())
        assertTrue(pipe.offer("efgh".toByteArray(Charsets.UTF_8)))

        pipe.finish()
        val out = ByteArrayOutputStream()
        pipe.copyTo(out)
        // 'a' was consumed by the explicit read above; the rest is delivered in order.
        assertEquals("bcdefgh", out.toString(Charsets.UTF_8))
    }

    @Test
    fun `abort ends the stream and rejects further writes`() {
        val pipe = StreamPipe(capacityBytes = 64, writeTimeoutMs = 100)
        assertTrue(pipe.offer("abc".toByteArray(Charsets.UTF_8)))
        pipe.abort()

        assertFalse(pipe.offer("def".toByteArray(Charsets.UTF_8)))
        val out = ByteArrayOutputStream()
        pipe.copyTo(out)
        assertEquals("abc", out.toString(Charsets.UTF_8))
    }

    @Test
    fun `abort releases a producer blocked in offer`() {
        val pipe = StreamPipe(capacityBytes = 4, writeTimeoutMs = 10_000)
        assertTrue(pipe.offer("abcd".toByteArray(Charsets.UTF_8))) // fill the budget

        var result: Boolean? = null
        val producer = Thread {
            result = pipe.offer("efgh".toByteArray(Charsets.UTF_8))
        }
        producer.start()
        Thread.sleep(50) // let it block waiting for byte budget

        val start = System.nanoTime()
        pipe.abort()
        producer.join(2_000)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertFalse("producer should have been unblocked by abort", producer.isAlive)
        assertEquals(false, result)
        assertTrue("abort should unblock quickly, took ${elapsedMs}ms", elapsedMs < 1_000)
    }

    @Test
    fun `close behaves like abort`() {
        val pipe = StreamPipe(capacityBytes = 8, writeTimeoutMs = 100)
        assertTrue(pipe.offer("xy".toByteArray(Charsets.UTF_8)))
        pipe.close()
        assertFalse(pipe.offer("z".toByteArray(Charsets.UTF_8)))
        val out = ByteArrayOutputStream()
        pipe.copyTo(out)
        assertEquals("xy", out.toString(Charsets.UTF_8))
    }
}