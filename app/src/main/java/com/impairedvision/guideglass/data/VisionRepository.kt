package com.impairedvision.guideglass.data

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.impairedvision.guideglass.vision.ObstacleDetector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the ImageAnalysis use case and maintains a LIFO buffer of camera frames.
 * Must be instantiated with ApplicationContext to prevent memory leaks.
 */
class VisionRepository(private val context: Context) {

    // LIFO buffer for Gemini context building
    private val latestFrame = AtomicReference<Bitmap?>(null)

    private val obstacleDetector = ObstacleDetector()

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    fun shutdown() {
        obstacleDetector.close()
        cameraExecutor.shutdown()
    }

    fun getLatestFrame(): Bitmap? {
        return latestFrame.get()
    }

    /**
     * Builds the ImageAnalysis use case to be bound to the camera lifecycle by the Activity.
     * The conversion function is passed in to keep the repository agnostic of specific conversion logic.
     * The onDangerDetected callback triggers sub-100ms urgent alerts directly from the detector.
     */
    fun buildImageAnalysisUseCase(
        toBitmap: (ImageProxy) -> Bitmap?,
        onDangerDetected: () -> Unit
    ): ImageAnalysis {
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmap = toBitmap(imageProxy)
            imageProxy.close()
            if (bitmap != null) {
                // Update LIFO buffer for Gemini
                latestFrame.set(bitmap)
                // Fast obstacle detection path
                obstacleDetector.processFrame(bitmap, onDangerDetected)
            }
        }
        return imageAnalysis
    }
}
