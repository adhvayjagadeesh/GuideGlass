package com.impairedvision.guideglass.vision

import kotlin.math.tan

/** The reflex gates that are expressed as fractions of the camera frame. */
data class ReflexGates(
        val centerPathMinXRatio: Float,
        val centerPathMaxXRatio: Float,
        val nearHeightRatio: Float,
        val nearAreaRatio: Float,
        val minLoomHeightRatio: Float,
        val minLoomAreaRatio: Float,
)

/**
 * Rescales the reflex gates when the camera's field of view changes.
 *
 * Every size gate in [ObstacleDetector] is a fraction of the frame, so its physical meaning depends
 * on the lens. A box that fills 34 percent of a 60 degree frame is a larger object than 34 percent
 * of a 92 degree frame, and the 0.32 to 0.68 corridor covers a wider angle through a wider lens.
 *
 * The simulator (paper/simulation/sim_glasses.py) shows what happens if the glasses feed the phone
 * gates unchanged: the first-alert distance for a 0.9 m bollard falls from 3.71 m to 2.29 m,
 * because the looming detector's minimum-size gate is no longer met at the time-to-contact radius.
 * Rescaling restores the phone's behavior exactly. Growth rate and time to contact are ratios and
 * need no change.
 *
 * The shipped gates were tuned for the phone, so they are the reference and every other lens is
 * mapped back to the phone's physical meaning.
 */
object ReflexScaling {

    /** Phone camera assumed by the simulator that the shipped gates are validated against. */
    const val PHONE_HFOV_DEG = 46f
    const val PHONE_VFOV_DEG = 60f

    /**
     * Ray-Ban Meta stream, portrait 9:16. UNVERIFIED: derived from a reported 100 degree diagonal
     * lens, not an official figure. Measure on hardware before trusting field results, and pass the
     * measured values to [forFieldOfView].
     */
    const val GLASSES_HFOV_DEG = 60.6f
    const val GLASSES_VFOV_DEG = 92.2f

    /** The shipped gates, unchanged. */
    val phone: ReflexGates =
            ReflexGates(
                    centerPathMinXRatio = ObstacleDetector.ReflexTuning.CENTER_PATH_MIN_X_RATIO,
                    centerPathMaxXRatio = ObstacleDetector.ReflexTuning.CENTER_PATH_MAX_X_RATIO,
                    nearHeightRatio = ObstacleDetector.ReflexTuning.NEAR_HEIGHT_RATIO,
                    nearAreaRatio = ObstacleDetector.ReflexTuning.NEAR_AREA_RATIO,
                    minLoomHeightRatio = ObstacleDetector.MIN_LOOM_HEIGHT_RATIO,
                    minLoomAreaRatio = ObstacleDetector.MIN_LOOM_AREA_RATIO,
            )

    /** Default gates for the Ray-Ban Meta stream. See the caveat on [GLASSES_HFOV_DEG]. */
    val glasses: ReflexGates
        get() = forFieldOfView(GLASSES_HFOV_DEG, GLASSES_VFOV_DEG)

    /** Gates that keep the phone's physical meaning for a camera with the given field of view. */
    fun forFieldOfView(hfovDeg: Float, vfovDeg: Float): ReflexGates {
        require(hfovDeg in 1f..179f && vfovDeg in 1f..179f) { "field of view out of range" }
        // The corridor maps linearly to angle in the simulator's model, so keep its half-angle.
        val halfAngle = (phone.centerPathMaxXRatio - 0.5f) * PHONE_HFOV_DEG
        val heightScale = k(PHONE_VFOV_DEG) / k(vfovDeg)
        val areaScale = heightScale * k(PHONE_HFOV_DEG) / k(hfovDeg)
        return ReflexGates(
                centerPathMinXRatio = 0.5f - halfAngle / hfovDeg,
                centerPathMaxXRatio = 0.5f + halfAngle / hfovDeg,
                nearHeightRatio = phone.nearHeightRatio * heightScale,
                nearAreaRatio = phone.nearAreaRatio * areaScale,
                minLoomHeightRatio = phone.minLoomHeightRatio * heightScale,
                minLoomAreaRatio = phone.minLoomAreaRatio * areaScale,
        )
    }

    /** Pinhole frame extent per unit distance: 2 tan(fov / 2). */
    private fun k(fovDeg: Float): Float = (2.0 * tan(Math.toRadians(fovDeg / 2.0))).toFloat()
}
