package com.impairedvision.guideglass.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Yuv420Test {

    private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    private fun assertColor(expected: Triple<Int, Int, Int>, argb: Int, tol: Int = 3) {
        val got = rgb(argb)
        val ok = abs(got.first - expected.first) <= tol &&
                abs(got.second - expected.second) <= tol &&
                abs(got.third - expected.third) <= tol
        assertTrue("expected $expected got $got", ok)
        assertEquals("alpha must be opaque", 0xFF, (argb ushr 24) and 0xFF)
    }

    @Test
    fun `BT601 limited range maps reference colors`() {
        assertColor(Triple(255, 255, 255), Yuv420.yuvToArgb(235, 128, 128))
        assertColor(Triple(0, 0, 0), Yuv420.yuvToArgb(16, 128, 128))
        assertColor(Triple(255, 0, 0), Yuv420.yuvToArgb(81, 90, 240))
        assertColor(Triple(0, 255, 0), Yuv420.yuvToArgb(145, 54, 34))
        assertColor(Triple(0, 0, 255), Yuv420.yuvToArgb(41, 240, 110))
    }

    @Test
    fun `out of range inputs clamp instead of wrapping`() {
        assertColor(Triple(0, 0, 0), Yuv420.yuvToArgb(0, 128, 128), tol = 0)
        assertColor(Triple(255, 255, 255), Yuv420.yuvToArgb(255, 128, 128), tol = 0)
    }

    @Test
    fun `packed size rounds chroma up for odd dimensions`() {
        assertEquals(4 * 2 + 2 * 2 * 1, Yuv420.expectedSize(4, 2))
        assertEquals(9 + 2 * 4, Yuv420.expectedSize(3, 3))
        assertEquals(504 * 896 * 3 / 2, Yuv420.expectedSize(504, 896)) // toolkit MEDIUM
    }

    /** A 4x2 I420 frame: left half red, right half blue. */
    private fun redBlueI420(): ByteArray {
        val red = intArrayOf(81, 90, 240)
        val blue = intArrayOf(41, 240, 110)
        val y = ByteArray(8) { i -> (if (i % 4 < 2) red[0] else blue[0]).toByte() }
        val u = byteArrayOf(red[1].toByte(), blue[1].toByte())
        val v = byteArrayOf(red[2].toByte(), blue[2].toByte())
        return y + u + v
    }

    @Test
    fun `I420 decodes left red and right blue`() {
        val out = IntArray(8)
        Yuv420.toArgb(redBlueI420(), 4, 2, Yuv420Layout.I420, out)
        for (row in 0..1) {
            assertColor(Triple(255, 0, 0), out[row * 4 + 0])
            assertColor(Triple(255, 0, 0), out[row * 4 + 1])
            assertColor(Triple(0, 0, 255), out[row * 4 + 2])
            assertColor(Triple(0, 0, 255), out[row * 4 + 3])
        }
    }

    @Test
    fun `NV12 and NV21 address the interleaved chroma`() {
        // Same picture written semi-planar: Y, then (U,V) pairs for NV12 or (V,U) for NV21.
        val y = redBlueI420().copyOfRange(0, 8)
        val nv12 = y + byteArrayOf(90, 240.toByte(), 240.toByte(), 110)
        val nv21 = y + byteArrayOf(240.toByte(), 90, 110, 240.toByte())
        for ((bytes, layout) in listOf(nv12 to Yuv420Layout.NV12, nv21 to Yuv420Layout.NV21)) {
            val out = IntArray(8)
            Yuv420.toArgb(bytes, 4, 2, layout, out)
            assertColor(Triple(255, 0, 0), out[0])
            assertColor(Triple(0, 0, 255), out[3])
        }
    }

    @Test
    fun `decoding with the wrong layout gives visibly wrong colors`() {
        // This is what makes the instrumented color-bar test meaningful: a layout mistake changes
        // the colors rather than leaving them intact, so it cannot pass by accident.
        //
        // Red and green on purpose. Red and blue share a chroma value (blue's U equals red's V,
        // both 240), and in a frame only two chroma samples wide that coincidence lets NV12 read
        // I420's bytes back unchanged. Red and green share none.
        val red = intArrayOf(81, 90, 240)
        val green = intArrayOf(145, 54, 34)
        val y = ByteArray(8) { i -> (if (i % 4 < 2) red[0] else green[0]).toByte() }
        val i420 = y + byteArrayOf(red[1].toByte(), green[1].toByte()) +
                byteArrayOf(red[2].toByte(), green[2].toByte())
        val right = IntArray(8)
        val wrong = IntArray(8)
        Yuv420.toArgb(i420, 4, 2, Yuv420Layout.I420, right)
        Yuv420.toArgb(i420, 4, 2, Yuv420Layout.NV12, wrong)
        assertColor(Triple(255, 0, 0), right[0])
        assertColor(Triple(0, 255, 0), right[3])
        assertNotEquals(rgb(right[0]), rgb(wrong[0]))
        assertNotEquals(rgb(right[3]), rgb(wrong[3]))
    }

    @Test
    fun `a padded or wrong-sized buffer is rejected rather than guessed at`() {
        val padded = redBlueI420() + ByteArray(4)
        assertThrows(IllegalArgumentException::class.java) {
            Yuv420.toArgb(padded, 4, 2, Yuv420Layout.I420, IntArray(8))
        }
    }
}
