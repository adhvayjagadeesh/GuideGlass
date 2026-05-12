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
}