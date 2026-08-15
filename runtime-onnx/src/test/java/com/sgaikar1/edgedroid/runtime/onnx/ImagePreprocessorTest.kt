package com.sgaikar1.edgedroid.runtime.onnx

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePreprocessorTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `normalize maps pixels to chw with imagenet stats`() {
        // 1x2 image: red (255,0,0) then black (0,0,0)
        val pixels = intArrayOf(argb(255, 0, 0), argb(0, 0, 0))
        val mean = floatArrayOf(0.5f, 0.5f, 0.5f)
        val std = floatArrayOf(0.5f, 0.5f, 0.5f)
        val out = ImageNormalizer.toFloatCHW(pixels, width = 2, height = 1, mean = mean, std = std)
        // CHW order: R row (2), G row (2), B row (2)
        assertEquals(6, out.size)
        assertEquals(1.0f, out[0], 1e-6f)   // R of red: (1.0 - 0.5)/0.5
        assertEquals(-1.0f, out[1], 1e-6f)  // R of black: (0 - 0.5)/0.5
        assertEquals(-1.0f, out[2], 1e-6f)  // G of red
        assertEquals(-1.0f, out[3], 1e-6f)  // G of black
        assertEquals(-1.0f, out[4], 1e-6f)  // B of red
        assertEquals(-1.0f, out[5], 1e-6f)  // B of black
    }

    @Test
    fun `resize downscales to the top-left source pixel`() {
        val src = intArrayOf(
            argb(255, 0, 0), argb(0, 255, 0),
            argb(0, 0, 255), argb(255, 255, 255),
        )
        val out = ImageResize.resizePixels(src, srcW = 2, srcH = 2, dstW = 1, dstH = 1)
        assertEquals(1, out.size)
        // with fx=fy=0 the bilinear sample picks p00 (the red corner)
        val r = (out[0] shr 16) and 0xFF
        assertEquals(255, r)
    }

    @Test
    fun `resize preserves size when equal`() {
        val src = intArrayOf(argb(10, 20, 30), argb(40, 50, 60))
        val out = ImageResize.resizePixels(src, srcW = 2, srcH = 1, dstW = 2, dstH = 1)
        assertEquals(src[0], out[0])
        assertEquals(src[1], out[1])
    }
}
