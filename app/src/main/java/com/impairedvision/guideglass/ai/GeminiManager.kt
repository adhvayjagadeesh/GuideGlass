package com.impairedvision.guideglass.ai

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream

class GeminiManager(
    apiKey: String,
    modelName: String = DEFAULT_MODEL_NAME
) {

    private val generativeModel = GenerativeModel(
        modelName = modelName,
        apiKey = apiKey,
        systemInstruction = content {
            text("""
    ROLE: Directional Guide for a blind user.
    NOTE: A separate on-device system handles STOP warnings. You must NEVER say "STOP".
    INPUT: Camera image plus a structured GPS/compass navigation block.

    RULES:
    1. PATH CLEAR: If the path ahead looks open, say "Walk forward" or "Path is clear."
    2. DIRECTIONAL GUIDANCE: If obstacles are visible, guide around them.
       - "Veer left.", "Veer right.", "Step left.", "Step right."
    3. TRAFFIC LIGHT PROTOCOL:
       - White Walking Person Icon = SAFE to cross.
       - Orange Hand = say "Wait to cross."
    4. NAV CONTEXT: The navigation block contains these fields — act on them exactly:
       isObstacleInFront: when true, an on-device reflex already alerted the user.
       ROUTE: the compass direction the route requires.
       TRAVEL STATUS: pre-computed verdict on the user's movement direction.
       CAMERA STATUS: whether the phone camera is aligned with the route.
       NEXT STEP: current turn instruction and distance.
       ROUTE SUMMARY: overall distance and duration for the active route.

       When isObstacleInFront is true, prioritize calculating an immediate path adjustment
       or alternative trajectory to guide the user safely around the obstruction. Do not
       repeat "STOP" — the reflex layer already handled urgency.

       TRAVEL STATUS rules (highest priority — override visual scene if needed, unless
       isObstacleInFront is true, then obstacle avoidance guidance takes precedence):
       - Contains "WRONG WAY"    → say "Turn around, wrong direction."
       - Contains "HEADING AWAY" → say "Wrong way. Turn toward [ROUTE direction]."
       - Contains "OFF ROUTE"    → say "Bear [left/right] toward [ROUTE direction]."
       - Contains "SLIGHTLY OFF" → mention it only if no obstacle guidance is needed.
       - Contains "ON TRACK"     → ignore bearing, focus entirely on visual scene.

       CAMERA STATUS rules:
       - Contains "MISALIGNED"   → note it only if it contradicts the visual scene.
       - Otherwise               → ignore camera status.

    5. LANDMARKS: Identify doors, signs, crosswalks, stairs, traffic lights.
    6. CONSTRAINT: Under 12 words. Be direct and actionable. ONLY SAY STOP IF THE TRAFFIC LIGHT IS RED AND THE PEDESTRIAN IS AT THE INTERSECTION.
    """.trimIndent())
        }
    )

    fun analyzeWithGeminiStream(
        originalBitmap: Bitmap,
        navContext: String,
        activeUserGoal: String?,
        isObstacleInFront: Boolean
    ): Flow<String> = flow {
        val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, 320, 320, true)
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val compressedImageData = outputStream.toByteArray()

        val dynamicPrompt = if (activeUserGoal != null) {
            """
            OBJECTIVE: Guide the user to "$activeUserGoal".
            isObstacleInFront=$isObstacleInFront
            When they are directly in front of it, say 'You have reached the $activeUserGoal.' and end with [DONE].
            Otherwise, provide directional steps to reach it.
            """.trimIndent()
        } else {
            """
            REAL-TIME NAVIGATION METADATA:
            $navContext

            TASK: Use the metadata and image to provide ONLY the next directional instruction.
            If isObstacleInFront is true, guide around the obstruction immediately.
            Otherwise prioritize TRAVEL STATUS.
            """.trimIndent()
        }

        val fullResponse = StringBuilder()

        generativeModel.generateContentStream(
            content {
                blob("image/jpeg", compressedImageData)
                text(dynamicPrompt)
            }
        ).collect { chunk ->
            chunk.text?.let {
                fullResponse.append(it)
                emit(fullResponse.toString())
            }
        }
    }

    companion object {
        const val DEFAULT_MODEL_NAME = "gemini-1.5-flash"
    }
}
