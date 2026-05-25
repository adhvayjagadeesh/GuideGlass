package com.impairedvision.guideglass.ai

/**
 * Shared, structured navigation payload for Gemini — used by VisionActivity and NavigationViewModel.
 */
object NavContextBuilder {

    fun bearingDescription(bearing: Float): String =
            when {
                bearing < 22.5f || bearing >= 337.5f -> "North"
                bearing < 67.5f -> "Northeast"
                bearing < 112.5f -> "East"
                bearing < 157.5f -> "Southeast"
                bearing < 202.5f -> "South"
                bearing < 247.5f -> "Southwest"
                bearing < 292.5f -> "West"
                else -> "Northwest"
            }

    fun bearingDelta(a: Float, b: Float): Float {
        val diff = kotlin.math.abs(a - b) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    fun formatNavigationBlock(
            navigating: Boolean,
            routeBearing: Float,
            compassBearing: Float,
            gpsBearing: Float,
            hasValidGpsBearing: Boolean,
            instructionText: String,
            remainingDistanceText: String,
            routeSummary: String,
            isObstacleInFront: Boolean,
            travelVerdict: String,
            cameraVerdict: String
    ): String {
        if (!navigating) {
            return buildString {
                appendLine("NAVIGATION: inactive.")
                appendLine("isObstacleInFront=$isObstacleInFront")
            }
        }

        val routeDir = bearingDescription(routeBearing)
        val compassDir = bearingDescription(compassBearing)
        val travelBearing = if (hasValidGpsBearing) gpsBearing else compassBearing
        val travelSource = if (hasValidGpsBearing) "GPS" else "compass"
        val travelDir = bearingDescription(travelBearing)

        return buildString {
            appendLine("isObstacleInFront=$isObstacleInFront")
            appendLine("COMPASS FACING: $compassDir (${compassBearing.toInt()}°).")
            appendLine("DIRECTION OF TRAVEL ($travelSource): $travelDir (${travelBearing.toInt()}°).")
            appendLine("ROUTE: Go $routeDir (${routeBearing.toInt()}°).")
            appendLine("ROUTE SUMMARY: $routeSummary")
            appendLine("TRAVEL STATUS: $travelVerdict")
            appendLine("CAMERA STATUS: $cameraVerdict")
            appendLine("NEXT STEP: \"$instructionText\" in $remainingDistanceText.")
        }
    }
}
