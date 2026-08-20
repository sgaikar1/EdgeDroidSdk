package com.sgaikar1.edgedroid.runtime.tts

import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Kokoro voice style vectors.
 *
 * Each voice is a `voices/<id>.bin` file of float32 data laid out as `[510, 1, 256]`. Kokoro
 * conditions synthesis on the *number of phoneme tokens*: the runtime picks
 * `style = voices[min(tokenCount, 509)]`, a `(1, 256)` vector, and feeds it as the model's
 * `style` input. This mirrors the official onnx-community export (`voices[len(tokens)]`).
 */
object KokoroVoice {

    const val ROWS = 510
    const val DIM = 256

    /** Load a voice file and return its full style matrix as row-major `[510][256]`. */
    fun load(file: File): Array<FloatArray> {
        require(file.isFile) { "Voice file not found: ${file.absolutePath}" }
        val floats = readFloats(file)
        require(floats.size >= ROWS * DIM) {
            "Voice file '${file.name}' has ${floats.size} floats; expected at least ${ROWS * DIM}"
        }
        return Array(ROWS) { r -> floats.copyOfRange(r * DIM, (r + 1) * DIM) }
    }

    /** Resolve the `(1, 256)` style vector for a given phoneme token count. */
    fun styleFor(rows: Array<FloatArray>, tokenCount: Int): FloatArray {
        val idx = tokenCount.coerceIn(0, ROWS - 1)
        return rows[idx]
    }

    private fun readFloats(file: File): FloatArray {
        FileChannel.open(file.toPath()).use { channel ->
            val size = channel.size()
            require(size > 0) { "Voice file is empty: ${file.absolutePath}" }
            val bytes = java.nio.ByteBuffer.allocate(size.toInt())
            channel.read(bytes)
            bytes.flip()
            bytes.order(ByteOrder.LITTLE_ENDIAN)
            val fb = bytes.asFloatBuffer()
            val out = FloatArray(fb.remaining())
            fb.get(out)
            return out
        }
    }

    /** Build the `style` input tensor (a `(1, 256)` float buffer) for [tokenCount]. */
    fun styleTensor(rows: Array<FloatArray>, tokenCount: Int): FloatArray = styleFor(rows, tokenCount)
}
