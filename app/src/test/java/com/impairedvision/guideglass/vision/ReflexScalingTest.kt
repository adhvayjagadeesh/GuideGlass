package com.impairedvision.guideglass.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexScalingTest {

    private fun assertGates(expected: ReflexGates, actual: ReflexGates, tol: Float) {
        assertEquals(expected.centerPathMinXRatio, actual.centerPathMinXRatio, tol)
        assertEquals(expected.centerPathMaxXRatio, actual.centerPathMaxXRatio, tol)
        assertEquals(expected.nearHeightRatio, actual.nearHeightRatio, tol)
        assertEquals(expected.nearAreaRatio, actual.nearAreaRatio, tol)
        assertEquals(expected.minLoomHeightRatio, actual.minLoomHeightRatio, tol)
        assertEquals(expected.minLoomAreaRatio, actual.minLoomAreaRatio, tol)
    }

    @Test
    fun `the phone's own field of view returns the shipped gates`() {
        val same = ReflexScaling.forFieldOfView(
                ReflexScaling.PHONE_HFOV_DEG, ReflexScaling.PHONE_VFOV_DEG)
        assertGates(ReflexScaling.phone, same, 1e-5f)
    }

    @Test
    fun `glasses gates match the simulator's rescaled thresholds`() {
        // From paper/simulation/sim_glasses_results.json, configs.GLASSES_R.thresholds. If this
        // fails, the app and the simulator disagree and the simulation no longer describes the app.
        val fromSimulator = ReflexGates(
                centerPathMinXRatio = 0.3634f,
                centerPathMaxXRatio = 0.6366f,
                nearHeightRatio = 0.1890f,
                nearAreaRatio = 0.0485f,
                minLoomHeightRatio = 0.0889f,
                minLoomAreaRatio = 0.0121f,
        )
        assertGates(fromSimulator, ReflexScaling.glasses, 5e-4f)
    }

    @Test
    fun `the corridor stays centred and keeps its physical half-angle`() {
        val g = ReflexScaling.glasses
        assertEquals(0.5f, (g.centerPathMinXRatio + g.centerPathMaxXRatio) / 2f, 1e-6f)
        val phoneHalf = (ReflexScaling.phone.centerPathMaxXRatio - 0.5f) * ReflexScaling.PHONE_HFOV_DEG
        val glassesHalf = (g.centerPathMaxXRatio - 0.5f) * ReflexScaling.GLASSES_HFOV_DEG
        assertEquals(phoneHalf, glassesHalf, 1e-3f)
    }

    @Test
    fun `a wider lens makes every size gate smaller`() {
        val narrow = ReflexScaling.forFieldOfView(50f, 80f)
        val wide = ReflexScaling.forFieldOfView(80f, 112f)
        assertTrue(wide.nearHeightRatio < narrow.nearHeightRatio)
        assertTrue(wide.nearAreaRatio < narrow.nearAreaRatio)
        assertTrue(wide.minLoomHeightRatio < narrow.minLoomHeightRatio)
        assertTrue(wide.minLoomAreaRatio < narrow.minLoomAreaRatio)
    }
}
