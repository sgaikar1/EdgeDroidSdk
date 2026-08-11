package com.sgaikar1.edgedroid.runtime.onnx

import android.graphics.BitmapFactory

/**
 * Turns image bytes into a model-ready `pixel_values` float tensor ([1,3,H,W], RGB, CHW order,
 * normalized). Resize + normalize math is pure ([ImageResize], [ImageNormalizer]) so it can be
 * unit-tested on the JVM; only the decode needs Android.
 */
internal class ImagePreprocessor {

    fun prepare(bytes: ByteArray, width: Int, height: Int, mean: FloatArray, std: FloatArray): FloatArray {
        if (width <= 0 || height <= 0) throw IllegalArgumentException("invalid target size ${width}x$height")
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Could not decode image bytes")
        val pixels = IntArray(width * height)
        if (bmp.width == width && bmp.height == height) {
            bmp.getPixels(pixels, 0, width, 0, 0, width, height)
            bmp.recycle()
        } else {
            val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, width, height, true)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            scaled.recycle()
            bmp.recycle()
        }
        return ImageNormalizer.toFloatCHW(pixels, width, height, mean, std)
    }

    companion object {
        /** ImageNet normalization used by most vision models. */
        val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/**
 * ARGB int pixels → normalized float tensor in CHW order ([3, H, W]).
 */
internal object ImageNormalizer {
    fun toFloatCHW(pixels: IntArray, width: Int, height: Int, mean: FloatArray, std: FloatArray): FloatArray {
        require(mean.size == 3 && std.size == 3) { "mean/std must have 3 channels" }
        val out = FloatArray(3 * width * height)
        var idx = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                out[idx] = (r - mean[0]) / std[0]
                out[idx + width * height] = (g - mean[1]) / std[1]
                out[idx + 2 * width * height] = (b - mean[2]) / std[2]
                idx++
            }
        }
        return out
    }
}

/**
 * Bilinear resize of ARGB int pixels.
 */
internal object ImageResize {
    fun resizePixels(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        require(src.size == srcW * srcH) { "source pixel count mismatch" }
        val out = IntArray(dstW * dstH)
        val xRatio = if (dstW > 1) (srcW - 1).toFloat() / (dstW - 1) else 0f
        val yRatio = if (dstH > 1) (srcH - 1).toFloat() / (dstH - 1) else 0f
        for (y in 0 until dstH) {
            val sy = y * yRatio
            val y0 = sy.toInt().coerceIn(0, srcH - 1)
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val fy = sy - y0
            for (x in 0 until dstW) {
                val sx = x * xRatio
                val x0 = sx.toInt().coerceIn(0, srcW - 1)
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val fx = sx - x0
                val p00 = src[y0 * srcW + x0]
                val p10 = src[y0 * srcW + x1]
                val p01 = src[y1 * srcW + x0]
                val p11 = src[y1 * srcW + x1]
                out[y * dstW + x] = bilinear(p00, p10, p01, p11, fx, fy)
            }
        }
        return out
    }

    private fun bilinear(p00: Int, p10: Int, p01: Int, p11: Int, fx: Float, fy: Float): Int {
        fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt()
        val topR = lerp((p00 shr 16) and 0xFF, (p10 shr 16) and 0xFF, fx)
        val topG = lerp((p00 shr 8) and 0xFF, (p10 shr 8) and 0xFF, fx)
        val topB = lerp(p00 and 0xFF, p10 and 0xFF, fx)
        val botR = lerp((p01 shr 16) and 0xFF, (p11 shr 16) and 0xFF, fx)
        val botG = lerp((p01 shr 8) and 0xFF, (p11 shr 8) and 0xFF, fx)
        val botB = lerp(p01 and 0xFF, p11 and 0xFF, fx)
        val r = lerp(topR, botR, fy)
        val g = lerp(topG, botG, fy)
        val b = lerp(topB, botB, fy)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
