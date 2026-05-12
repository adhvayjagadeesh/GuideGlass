package com.impairedvision.guideglass.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

class ObstacleDetector {

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableClassification() // Enable if you want to know what the object is, but not strictly required for obstacles
            .build()
    )

    private var lastWarningTime = 0L
    private val WARNING_COOLDOWN_MS = 2000L

    /**
     * Processes a bitmap frame. If a collision risk is detected, [onDangerDetected] is triggered immediately.
     */
    fun processFrame(bitmap: Bitmap, onDangerDetected: () -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)

        detector.process(image)
            .addOnSuccessListener { detectedObjects ->
                val currentTime = System.currentTimeMillis()
                
                // Don't spam warnings
                if (currentTime - lastWarningTime < WARNING_COOLDOWN_MS) {
                    return@addOnSuccessListener
                }

                val frameWidth = bitmap.width
                val frameHeight = bitmap.height
                
                // We define the center column of the user's path.
                val centerXMin = frameWidth * 0.3f
                val centerXMax = frameWidth * 0.7f

                for (obj in detectedObjects) {
                    val box = obj.boundingBox
                    
                    // Does the object intersect the center path?
                    val inCenterPath = box.right > centerXMin && box.left < centerXMax
                    
                    // The primary distance proxy: Bottom edge Y-coordinate.
                    // If the object's bottom edge is near the bottom of the frame (e.g., > 80% of frame height),
                    // it is physically close to the user's feet/waist.
                    val bottomEdgeRatio = box.bottom.toFloat() / frameHeight

                    if (inCenterPath && bottomEdgeRatio > 0.8f) {
                        Log.d("ObstacleDetector", "URGENT STOP: Object detected at bottom edge ($bottomEdgeRatio) in center path.")
                        lastWarningTime = currentTime
                        onDangerDetected()
                        break // Trigger once per frame max
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("ObstacleDetector", "Object detection failed", e)
            }
    }

    fun close() {
        detector.close()
    }
}
