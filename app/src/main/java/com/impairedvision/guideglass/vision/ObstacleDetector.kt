package com.impairedvision.guideglass.vision

import android.util.Log
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ML Kit STREAM_MODE obstacle reflex with **edge-triggered** alerts.
 *
 * ## Proximity model (why this is reliable on a mono camera)
 *
 * A bounding box's *bottom edge* is a poor distance cue — the ground plane, shadows, and a
 * downward phone tilt all push distant objects into the lower frame rows. Instead we treat an
 * obstacle as "near" when it is **large in apparent size** (a close object looms large) and/or
 * **looming** (its tracked size is growing fast / time-to-contact is short). Looming uses the
 * per-object [DetectedObject.trackingId] that STREAM_MODE provides, sampled over a short window.
 *
 * ## Tuning guide (street / phone-in-hand, held at chest/waist height, angled slightly down)
 *
 * | Property | Default | Raise → | Lower → |
 * |----------|---------|---------|---------|
 * | [centerPathMinXRatio] / [centerPathMaxXRatio] | 0.32 / 0.68 | Narrower corridor, fewer side FPs | Wider, catch side approaches |
 * | [nearHeightRatio] | 0.34 | Only very close objects (fewer FPs) | Triggers farther (fewer misses) |
 * | [enterStreakRequired] | 2 | Fewer flicker FPs, slower STOP | Faster STOP |
 * | [clearStreakRequired] | 4 | Longer "still blocked" hold | Faster re-arm |
 */
class ObstacleDetector(
    centerPathMinXRatio: Float = ReflexTuning.CENTER_PATH_MIN_X_RATIO,
    centerPathMaxXRatio: Float = ReflexTuning.CENTER_PATH_MAX_X_RATIO,
    minCorridorOverlapRatio: Float = ReflexTuning.MIN_CORRIDOR_OVERLAP_RATIO,
    maxBoxWidthRatio: Float = ReflexTuning.MAX_BOX_WIDTH_RATIO,
    nearHeightRatio: Float = ReflexTuning.NEAR_HEIGHT_RATIO,
    nearAreaRatio: Float = ReflexTuning.NEAR_AREA_RATIO,
    enterStreakRequired: Int = ReflexTuning.ENTER_STREAK_REQUIRED,
    clearStreakRequired: Int = ReflexTuning.CLEAR_STREAK_REQUIRED
) {

    /** Conservative, locally-computed avoidance direction emitted alongside an obstacle alert. */
    enum class AvoidanceHint { NONE, VEER_LEFT, VEER_RIGHT }

    /** Snapshot of the triggering obstacle, used by the Activity for an immediate spoken hint. */
    data class ReflexDecision(
        val avoidance: AvoidanceHint,
        val obstacleCenterXRatio: Float,
        val heightFraction: Float
    )

    interface Listener {
        fun onObstacleEntered(decision: ReflexDecision)
        fun onObstacleCleared()
    }

    var centerPathMinXRatio: Float = centerPathMinXRatio
        private set
    var centerPathMaxXRatio: Float = centerPathMaxXRatio
        private set
    var minCorridorOverlapRatio: Float = minCorridorOverlapRatio
        private set

    /** Boxes wider than this fraction of the frame are background (wall/ground/sky), not obstacles. */
    var maxBoxWidthRatio: Float = maxBoxWidthRatio
        private set

    /** Primary proximity gate: box height / frameHeight above this ⇒ "near". */
    var nearHeightRatio: Float = nearHeightRatio
        private set

    /** Alternate proximity gate: box area / frame area above this ⇒ "near" (wide/low objects). */
    var nearAreaRatio: Float = nearAreaRatio
        private set

    /** Consecutive danger frames before [Listener.onObstacleEntered] (suppresses flicker FPs). */
    var enterStreakRequired: Int = enterStreakRequired
        private set

    /** Consecutive clear frames before [Listener.onObstacleCleared]. */
    var clearStreakRequired: Int = clearStreakRequired
        private set

    private val detector =
            ObjectDetection.getClient(
                    ObjectDetectorOptions.Builder()
                            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                            // Evaluate every candidate (and get per-object trackingId for looming),
                            // not just the single prominent tracked object.
                            .enableMultipleObjects()
                            .enableClassification()
                            .build()
            )

    @Volatile private var pathObstaclePresent = false
    private var enterStreak = 0
    private var clearStreak = 0

    private data class TrackSample(val tMs: Long, val heightFrac: Float)

    private val trackHistory = HashMap<Int, ArrayDeque<TrackSample>>()
    private val trackLastSeen = HashMap<Int, Long>()

    fun isObstaclePresent(): Boolean = pathObstaclePresent

    fun applyTuning(
            centerPathMinXRatio: Float = this.centerPathMinXRatio,
            centerPathMaxXRatio: Float = this.centerPathMaxXRatio,
            minCorridorOverlapRatio: Float = this.minCorridorOverlapRatio,
            maxBoxWidthRatio: Float = this.maxBoxWidthRatio,
            nearHeightRatio: Float = this.nearHeightRatio,
            nearAreaRatio: Float = this.nearAreaRatio,
            enterStreakRequired: Int = this.enterStreakRequired,
            clearStreakRequired: Int = this.clearStreakRequired
    ) {
        this.centerPathMinXRatio = centerPathMinXRatio
        this.centerPathMaxXRatio = centerPathMaxXRatio
        this.minCorridorOverlapRatio = minCorridorOverlapRatio
        this.maxBoxWidthRatio = maxBoxWidthRatio
        this.nearHeightRatio = nearHeightRatio
        this.nearAreaRatio = nearAreaRatio
        this.enterStreakRequired = enterStreakRequired
        this.clearStreakRequired = clearStreakRequired
    }

    fun processFrame(bitmap: Bitmap, listener: Listener) {
        val image = InputImage.fromBitmap(bitmap, 0)

        detector
                .process(image)
                .addOnSuccessListener { detectedObjects ->
                    val now = System.currentTimeMillis()
                    val decision = evaluatePathObstacle(detectedObjects, bitmap.width, bitmap.height, now)

                    if (decision != null) {
                        clearStreak = 0
                        if (!pathObstaclePresent) {
                            enterStreak++
                            if (enterStreak >= enterStreakRequired) {
                                pathObstaclePresent = true
                                enterStreak = 0
                                Log.d(TAG, "Obstacle entered: hint=${decision.avoidance} h=${decision.heightFraction}")
                                listener.onObstacleEntered(decision)
                            }
                        }
                    } else {
                        enterStreak = 0
                        if (pathObstaclePresent) {
                            clearStreak++
                            if (clearStreak >= clearStreakRequired) {
                                pathObstaclePresent = false
                                clearStreak = 0
                                Log.d(TAG, "Obstacle cleared")
                                listener.onObstacleCleared()
                            }
                        }
                    }
                }
                .addOnFailureListener { e -> Log.e(TAG, "Object detection failed", e) }
    }

    /**
     * @return a [ReflexDecision] if a near/looming obstacle blocks the walking corridor, else null.
     */
    private fun evaluatePathObstacle(
            detectedObjects: List<DetectedObject>,
            frameWidth: Int,
            frameHeight: Int,
            now: Long
    ): ReflexDecision? {
        if (frameWidth <= 0 || frameHeight <= 0) return null

        cleanupTracks(now)

        val corridorMinX = frameWidth * centerPathMinXRatio
        val corridorMaxX = frameWidth * centerPathMaxXRatio
        val corridorWidth = corridorMaxX - corridorMinX
        val frameArea = frameWidth.toFloat() * frameHeight.toFloat()
        val midX = frameWidth / 2f

        // Occupancy of each side of the frame by any sizable object — used for the avoidance hint.
        var leftOcc = 0f
        var rightOcc = 0f

        var dangerObj: DetectedObject? = null
        var dangerHeightFrac = 0f

        for (obj in detectedObjects) {
            val box = obj.boundingBox
            val boxWidth = box.width().toFloat()
            val boxHeight = box.height().toFloat()
            if (boxWidth <= 0f || boxHeight <= 0f) continue
            if (boxWidth >= frameWidth * maxBoxWidthRatio) continue // background span

            val heightFrac = boxHeight / frameHeight
            val areaFrac = (boxWidth * boxHeight) / frameArea
            val centerX = box.exactCenterX()

            // Side-occupancy for hinting (any reasonably sized object, regardless of corridor).
            if (heightFrac >= HINT_SIDE_MIN_HEIGHT) {
                if (centerX < midX) leftOcc = max(leftOcc, heightFrac)
                else rightOcc = max(rightOcc, heightFrac)
            }

            // Horizontal corridor gate: box center inside corridor, or large overlap of it.
            val centerInCorridor = centerX in corridorMinX..corridorMaxX
            val overlap = min(box.right.toFloat(), corridorMaxX) - max(box.left.toFloat(), corridorMinX)
            val overlapFrac = if (corridorWidth > 0f) overlap / corridorWidth else 0f
            if (!centerInCorridor && overlapFrac < minCorridorOverlapRatio) continue

            // Looming via tracked size growth (only meaningful for tracked objects).
            val looming = obj.trackingId?.let { id ->
                updateTrack(id, now, heightFrac)
                isLooming(trackHistory[id], heightFrac, areaFrac)
            } ?: false

            val nearBySize = heightFrac >= nearHeightRatio || areaFrac >= nearAreaRatio
            if (!nearBySize && !looming) continue

            if (heightFrac > dangerHeightFrac) {
                dangerHeightFrac = heightFrac
                dangerObj = obj
            }
        }

        val primary = dangerObj ?: return null
        val centerRatio = primary.boundingBox.exactCenterX() / frameWidth
        return ReflexDecision(
                avoidance = computeHint(centerRatio, leftOcc, rightOcc),
                obstacleCenterXRatio = centerRatio,
                heightFraction = dangerHeightFrac
        )
    }

    private fun updateTrack(id: Int, now: Long, heightFrac: Float) {
        val hist = trackHistory.getOrPut(id) { ArrayDeque() }
        hist.addLast(TrackSample(now, heightFrac))
        while (hist.size > 1 && now - hist.first().tMs > LOOM_WINDOW_MS) hist.removeFirst()
        while (hist.size > MAX_SAMPLES_PER_TRACK) hist.removeFirst()
        trackLastSeen[id] = now
    }

    /** True if the tracked object is growing quickly or has a short estimated time-to-contact. */
    private fun isLooming(hist: ArrayDeque<TrackSample>?, currHeight: Float, currArea: Float): Boolean {
        if (hist == null || hist.size < 2) return false
        if (currHeight < MIN_LOOM_HEIGHT_RATIO && currArea < MIN_LOOM_AREA_RATIO) return false

        val first = hist.first()
        val last = hist.last()
        val dt = (last.tMs - first.tMs) / 1000f
        if (dt <= 0f || first.heightFrac <= 0f) return false

        val dh = last.heightFrac - first.heightFrac
        if (dh <= 0f) return false

        val growthRate = (dh / first.heightFrac) / dt
        if (growthRate >= LOOM_GROWTH_RATE_PER_SEC) return true

        val ttc = dt * first.heightFrac / dh
        return ttc in 0f..TTC_IMMINENT_SEC
    }

    /** Conservative: only hint when the obstacle is clearly off-center AND the other side is open. */
    private fun computeHint(centerRatio: Float, leftOcc: Float, rightOcc: Float): AvoidanceHint {
        if (centerRatio in CENTER_NEUTRAL_MIN..CENTER_NEUTRAL_MAX) return AvoidanceHint.NONE
        if (abs(leftOcc - rightOcc) < HINT_OCC_DIFF) return AvoidanceHint.NONE
        return if (centerRatio < 0.5f) {
            if (rightOcc <= HINT_CLEAR_OCC) AvoidanceHint.VEER_RIGHT else AvoidanceHint.NONE
        } else {
            if (leftOcc <= HINT_CLEAR_OCC) AvoidanceHint.VEER_LEFT else AvoidanceHint.NONE
        }
    }

    private fun cleanupTracks(now: Long) {
        val it = trackLastSeen.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value > TRACK_TTL_MS) {
                trackHistory.remove(entry.key)
                it.remove()
            }
        }
    }

    fun close() {
        detector.close()
        trackHistory.clear()
        trackLastSeen.clear()
    }

    object ReflexTuning {
        const val CENTER_PATH_MIN_X_RATIO = 0.32f
        const val CENTER_PATH_MAX_X_RATIO = 0.68f
        const val MIN_CORRIDOR_OVERLAP_RATIO = 0.45f
        const val MAX_BOX_WIDTH_RATIO = 0.90f
        const val NEAR_HEIGHT_RATIO = 0.34f
        const val NEAR_AREA_RATIO = 0.12f
        const val ENTER_STREAK_REQUIRED = 2
        const val CLEAR_STREAK_REQUIRED = 4
    }

    companion object {
        private const val TAG = "ObstacleDetector"

        // Looming / time-to-contact.
        private const val LOOM_WINDOW_MS = 900L
        private const val TRACK_TTL_MS = 1200L
        private const val MAX_SAMPLES_PER_TRACK = 6
        private const val MIN_LOOM_HEIGHT_RATIO = 0.16f
        private const val MIN_LOOM_AREA_RATIO = 0.03f
        private const val LOOM_GROWTH_RATE_PER_SEC = 0.6f
        private const val TTC_IMMINENT_SEC = 2.0f

        // Avoidance hint geometry.
        private const val HINT_SIDE_MIN_HEIGHT = 0.15f
        private const val HINT_OCC_DIFF = 0.20f
        private const val HINT_CLEAR_OCC = 0.35f
        private const val CENTER_NEUTRAL_MIN = 0.46f
        private const val CENTER_NEUTRAL_MAX = 0.54f
    }
}
