package com.impairedvision.guideglass.glasses

/** Chroma layouts a decoded YUV 4:2:0 frame can arrive in. */
enum class Yuv420Layout {
    /** Planar: full Y plane, then the U plane, then the V plane. */
    I420,

    /** Semi-planar: full Y plane, then interleaved U,V pairs. */
    NV12,

    /** Semi-planar: full Y plane, then interleaved V,U pairs. */
    NV21,
}

/**
 * YUV 4:2:0 to ARGB conversion for raw glasses video frames.
 *
 * Pure Kotlin with no Android types, so the arithmetic is unit tested on the JVM. Uses BT.601
 * limited-range coefficients, the convention Android's video decoders output.
 *
 * A wrong [Yuv420Layout] does not crash. It swaps or scrambles the chroma, so the obstacle detector
 * quietly sees wrong colors. That failure is silent, which is why the layout is pinned by an
 * instrumented test that streams known color bars through Meta's mock glasses.
 */
object Yuv420 {

    /** Bytes in a tightly packed 4:2:0 frame. Chroma planes round up for odd dimensions. */
    fun expectedSize(width: Int, height: Int): Int {
        val chroma = ((width + 1) / 2) * ((height + 1) / 2)
        return width * height + 2 * chroma
    }

    /**
     * Converts [src] to ARGB_8888 into [out], which must hold at least `width * height` ints.
     *
     * @throws IllegalArgumentException if [src] is not exactly one tightly packed frame. A size
     *   mismatch means row padding or a different format, and guessing would feed the detector
     *   garbage, so the caller is forced to handle it.
     */
    fun toArgb(src: ByteArray, width: Int, height: Int, layout: Yuv420Layout, out: IntArray) {
        require(width > 0 && height > 0) { "bad frame size ${width}x$height" }
        val expected = expectedSize(width, height)
        require(src.size == expected) {
            "expected $expected bytes for a packed ${width}x$height 4:2:0 frame, got ${src.size}"
        }
        require(out.size >= width * height) { "output buffer too small" }

        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        val ySize = width * height
        val chromaPlane = chromaW * chromaH

        for (row in 0 until height) {
            val chromaRow = row / 2
            for (col in 0 until width) {
                val y = src[row * width + col].toInt() and 0xFF
                val chromaCol = col / 2
                val u: Int
                val v: Int
                when (layout) {
                    Yuv420Layout.I420 -> {
                        val i = chromaRow * chromaW + chromaCol
                        u = src[ySize + i].toInt() and 0xFF
                        v = src[ySize + chromaPlane + i].toInt() and 0xFF
                    }
                    Yuv420Layout.NV12 -> {
                        val i = ySize + (chromaRow * chromaW + chromaCol) * 2
                        u = src[i].toInt() and 0xFF
                        v = src[i + 1].toInt() and 0xFF
                    }
                    Yuv420Layout.NV21 -> {
                        val i = ySize + (chromaRow * chromaW + chromaCol) * 2
                        v = src[i].toInt() and 0xFF
                        u = src[i + 1].toInt() and 0xFF
                    }
                }
                out[row * width + col] = yuvToArgb(y, u, v)
            }
        }
    }

    /** One BT.601 limited-range pixel. Internal so tests can check known colors directly. */
    internal fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val c = y - 16
        val d = u - 128
        val e = v - 128
        val r = clamp((298 * c + 409 * e + 128) shr 8)
        val g = clamp((298 * c - 100 * d - 208 * e + 128) shr 8)
        val b = clamp((298 * c + 516 * d + 128) shr 8)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(x: Int): Int = if (x < 0) 0 else if (x > 255) 255 else x
}
