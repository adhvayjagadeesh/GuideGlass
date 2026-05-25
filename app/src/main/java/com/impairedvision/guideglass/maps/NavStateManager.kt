package com.impairedvision.guideglass.maps

// Singleton to share navigation state between MapsActivity and VisionActivity
object NavStateManager {
    @Volatile
    var currentInstruction: String = "No active navigation."

    @Volatile
    var remainingDistance: String = ""

    @Volatile
    var routeBearing: Float = 0f       // Intended direction of the current step

    @Volatile
    var currentStepIndex: Int = 0      // Which step the user is currently on

    /** Set by ML Kit reflex layer; consumed by Gemini nav context. */
    @Volatile
    var isObstacleInFront: Boolean = false

    /** Overall route metrics, e.g. "2.1 km • 28 min". */
    @Volatile
    var routeSummary: String = ""
}