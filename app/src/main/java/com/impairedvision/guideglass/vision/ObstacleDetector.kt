package com.impairedvision.guideglass.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

/**
 * ML Kit STREAM_MODE obstacle reflex with **edge-triggered** alerts.
 *
 * ## Tuning guide (street / phone-in-hand)
 *
 * Mono-camera depth is approximated by bounding-box position. Adjust these properties on a shared
 * instance (see [ReflexTuning] defaults) and test outdoors with the device held at chest/waist
 * height, angled slightly downward — typical blind-navigation posture.
 *
 * | Property | Default | Raise → | Lower → |
 * |----------|---------|---------|---------|
 * | [centerPathMinXRatio] / [centerPathMaxXRatio] | 0.3 / 0.7 | Narrower path, fewer side false positives | Wider path, catch side approaches |
 * | [bottomEdgeProximityRatio] | 0.8 | Triggers when object is farther (fewer misses) | Only very close objects (fewer ground/floor FPs) |
 * | [clearStreakRequired] | 3 | Slower “cleared” → longer silence while blocking | Faster re-arm for second STOP |
 *
 * **Phone tilt:** If the floor fills the bottom of the frame, *lower* [bottomEdgeProximityRatio]
 * (e.g. 0.85–0.9). If obstacles are detected too late, *lower* slightly (e.g. 0.75).
 */
class ObstacleDetector(
    centerPathMinXRatio: Float = ReflexTuning.CENTER_PATH_MIN_X_RATIO,
    centerPathMaxXRatio: Float = ReflexTuning.CENTER_PATH_MAX_X_RATIO,
    bottomEdgeProximityRatio: Float = ReflexTuning.BOTTOM_EDGE_PROXIMITY_RATIO,
    clearStreakRequired: Int = ReflexTuning.CLEAR_STREAK_REQUIRED
) {

    interface Listener {
        fun onObstacleEntered()
        fun onObstacleCleared()
    }

    /** Walkable corridor as fraction of frame width (left / right edges). */
    var centerPathMinXRatio: Float = centerPathMinXRatio
        private set

    /** Walkable corridor as fraction of frame width (left / right edges). */
    var centerPathMaxXRatio: Float = centerPathMaxXRatio
        private set

    /**
     * Object [android.graphics.Rect.bottom] / frameHeight above this ⇒ “in path”.
     * Increase if tilted phone triggers on pavement; decrease if alerts come too late.
     */
    var bottomEdgeProximityRatio: Float = bottomEdgeProximityRatio
        private set

    /**
     * Consecutive ML Kit frames with no center obstacle before [Listener.onObstacleCleared].
     * Increase if presence flickers; decrease to re-arm STOP sooner after clearing.
     */
    var clearStreakRequired: Int = clearStreakRequired
        private set

    private val detector =
            ObjectDetection.getClient(
                    ObjectDetectorOptions.Builder()
                            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                            .enableClassification()
                            .build()
            )

    @Volatile private var pathObstaclePresent = false
    private var clearStreak = 0

    fun isObstaclePresent(): Boolean = pathObstaclePresent

    /** Apply field-test tuning without reconstructing the detector client. */
    fun applyTuning(
            centerPathMinXRatio: Float = this.centerPathMinXRatio,
            centerPathMaxXRatio: Float = this.centerPathMaxXRatio,
            bottomEdgeProximityRatio: Float = this.bottomEdgeProximityRatio,
            clearStreakRequired: Int = this.clearStreakRequired
    ) {
        this.centerPathMinXRatio = centerPathMinXRatio
        this.centerPathMaxXRatio = centerPathMaxXRatio
        this.bottomEdgeProximityRatio = bottomEdgeProximityRatio
        this.clearStreakRequired = clearStreakRequired
    }

    fun processFrame(bitmap: Bitmap, listener: Listener) {
        val image = InputImage.fromBitmap(bitmap, 0)

        detector
                .process(image)
                .addOnSuccessListener { detectedObjects ->
                    val present = evaluatePathObstacle(detectedObjects, bitmap.width, bitmap.height)

                    if (present) {
                        clearStreak = 0
                        if (!pathObstaclePresent) {
                            pathObstaclePresent = true
                            Log.d(TAG, "Path obstacle entered (edge trigger)")
                            listener.onObstacleEntered()
                        }
                    } else if (pathObstaclePresent) {
                        clearStreak++
                        if (clearStreak >= clearStreakRequired) {
                            pathObstaclePresent = false
                            clearStreak = 0
                            Log.d(TAG, "Path obstacle cleared")
                            listener.onObstacleCleared()
                        }
                    }
                }
                .addOnFailureListener { e -> Log.e(TAG, "Object detection failed", e) }
    }

    private fun evaluatePathObstacle(
            detectedObjects: List<com.google.mlkit.vision.objects.DetectedObject>,
            frameWidth: Int,
            frameHeight: Int
    ): Boolean {
        val centerXMin = frameWidth * centerPathMinXRatio
        val centerXMax = frameWidth * centerPathMaxXRatio

        for (obj in detectedObjects) {
            val box = obj.boundingBox
            val inCenterPath = box.right > centerXMin && box.left < centerXMax
            val bottomEdgeRatio = box.bottom.toFloat() / frameHeight
            if (inCenterPath && bottomEdgeRatio > bottomEdgeProximityRatio) {
                return true
            }
        }
        return false
    }

    fun close() {
        detector.close()
    }

    object ReflexTuning {
        const val CENTER_PATH_MIN_X_RATIO = 0.3f
        const val CENTER_PATH_MAX_X_RATIO = 0.7f
        const val BOTTOM_EDGE_PROXIMITY_RATIO = 0.8f
        const val CLEAR_STREAK_REQUIRED = 3
    }

    companion object {
        private const val TAG = "ObstacleDetector"
    }
}
