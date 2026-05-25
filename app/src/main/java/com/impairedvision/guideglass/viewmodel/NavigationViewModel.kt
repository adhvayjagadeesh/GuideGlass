package com.impairedvision.guideglass.viewmodel

import android.location.Location
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.impairedvision.guideglass.data.LocationRepository
import com.impairedvision.guideglass.data.SensorRepository
import com.impairedvision.guideglass.data.VisionRepository
import com.impairedvision.guideglass.models.NavStep
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.impairedvision.guideglass.ai.GeminiManager
import com.impairedvision.guideglass.ai.NavContextBuilder
import com.impairedvision.guideglass.maps.NavStateManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

data class NavUiState(
    val navigating: Boolean = false,
    val destinationName: String? = null,
    val destinationLatLng: LatLng? = null,
    val navSteps: List<NavStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val routePolyline: List<LatLng>? = null,
    val compassBearing: Float = 0f,
    val gpsBearing: Float = 0f,
    val hasValidGpsBearing: Boolean = false,
    val isMoving: Boolean = false,
    val currentLocation: Location? = null,
    val instructionText: String = "",
    val statusText: String = "Ready",
    val activeUserGoal: String? = null,
    val remainingDistance: Float = 0f,
    val routeBearing: Float = 0f,
    val routeSummary: String = "",
    val isObstacleInFront: Boolean = false
)

sealed class NavEvent {
    data class Speak(val text: String, val urgent: Boolean = false) : NavEvent()
    data class SpeakNavigation(val text: String) : NavEvent()
    object VibrateSuccess : NavEvent()
    object VibrateError : NavEvent()
}

class NavigationViewModel(
    private val locationRepository: LocationRepository,
    private val sensorRepository: SensorRepository,
    private val visionRepository: VisionRepository,
    private val geminiManager: GeminiManager,
    private val routeRepository: com.impairedvision.guideglass.data.RouteRepository,
    private val directionsApiKey: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(NavUiState())
    val uiState: StateFlow<NavUiState> = _uiState.asStateFlow()

    private val _events = Channel<NavEvent>()
    val events = _events.receiveAsFlow()

    private var lastKnownLocation: Location? = null

    // Reactive Trigger State
    private val isAnalyzing = AtomicBoolean(false)
    private var lastAnalyzedBearing = 0f
    private var lastAnalyzedLocation: Location? = null
    private val userActionTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    
    // Hysteresis State for Navigation Verdict
    private var committedVerdictLevel = 0
    private var pendingVerdictLevel = 0
    private var pendingVerdictSince = 0L

    init {
        // Collect sensor data
        sensorRepository.compassBearing.onEach { bearing ->
            _uiState.update { it.copy(compassBearing = bearing) }
        }.launchIn(viewModelScope)

        sensorRepository.isMoving.onEach { moving ->
            _uiState.update { it.copy(isMoving = moving) }
        }.launchIn(viewModelScope)

        // Collect location data
        locationRepository.getLocationUpdates().onEach { location ->
            lastKnownLocation = location
            _uiState.update { 
                it.copy(
                    currentLocation = location,
                    gpsBearing = if (location.hasBearing()) location.bearing else it.gpsBearing,
                    hasValidGpsBearing = it.hasValidGpsBearing || location.hasBearing()
                ) 
            }
            if (_uiState.value.navigating) {
                checkNavigationProgress(location)
            }
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            sensorRepository.getSensorDataFlow().collect {}
        }

        // Setup Reactive Triggers for Gemini
        setupGeminiTriggers()
    }

    private fun setupGeminiTriggers() {
        // Reuse the already-active compassBearing StateFlow — no new GPS subscription needed.
        val headingFlow = sensorRepository.compassBearing
            .filter { bearingDelta(it, lastAnalyzedBearing) > 10f } // More sensitive heading (10 deg)
            .map { }

        // Reuse the location StateFlow already collected in init{} to avoid a double GPS callback.
        val locationFlow = _uiState
            .map { it.currentLocation }
            .filterNotNull()
            .filter { loc ->
                lastAnalyzedLocation == null || loc.distanceTo(lastAnalyzedLocation!!) > 2f // More sensitive movement (2m)
            }
            .map { }

        // Ticker to ensure guidance every 3s even if stationary
        val tickerFlow = flow {
            while (true) {
                emit(Unit)
                delay(3000L)
            }
        }

        viewModelScope.launch {
            merge(headingFlow, locationFlow, userActionTrigger, tickerFlow).collect {
                // Interruption protection: drop triggers while a Gemini window is active.
                if (isAnalyzing.get()) return@collect
                if (isAnalyzing.compareAndSet(false, true)) {
                    triggerGeminiAnalysis()
                }
            }
        }
    }

    private fun triggerGeminiAnalysis() {
        val bitmap = visionRepository.getLatestFrame()
        if (bitmap == null) {
            isAnalyzing.set(false)
            return
        }
        
        lastAnalyzedBearing = _uiState.value.compassBearing
        lastAnalyzedLocation = _uiState.value.currentLocation

        viewModelScope.launch(Dispatchers.IO) {
            updateStatus(if (_uiState.value.activeUserGoal != null) "Thinking..." else "Analyzing...")
            try {
                // Collect the full streamed response before speaking.
                // GeminiManager emits cumulative chunks; we want only the final complete string.
                var finalResponse = ""
                val obstacleFlag = NavStateManager.isObstacleInFront
                geminiManager.analyzeWithGeminiStream(
                        bitmap,
                        buildNavContext(),
                        _uiState.value.activeUserGoal,
                        obstacleFlag
                )
                    .collect { cumulativeChunk ->
                        finalResponse = cumulativeChunk
                    }

                if (finalResponse.endsWith("[DONE]")) {
                    _uiState.update { it.copy(activeUserGoal = null) }
                    _events.send(NavEvent.Speak(finalResponse.replace("[DONE]", "").trim(), urgent = true))
                } else if (finalResponse.isNotBlank()) {
                    _events.send(NavEvent.Speak(finalResponse, urgent = false))
                }
            } catch (e: Exception) {
                Log.e("NavigationViewModel", "Gemini error", e)
            } finally {
                updateStatus("Ready")
                // Short guard delay to prevent accidental spamming, but allowing 1.5-3s cycles
                delay(1000L) 
                isAnalyzing.set(false)
            }
        }
    }

    fun fetchAndStartNavigation(destinationName: String, destinationLatLng: LatLng) {
        val origin = _uiState.value.currentLocation
        if (origin == null) {
            viewModelScope.launch {
                _events.send(NavEvent.Speak("Waiting for GPS signal..."))
            }
            return
        }

        val originLatLng = LatLng(origin.latitude, origin.longitude)
        
        _uiState.update { it.copy(statusText = "Fetching route...") }
        
        viewModelScope.launch {
            val result = routeRepository.getWalkingDirections(originLatLng, destinationLatLng, directionsApiKey)
            if (result != null) {
                val summary = "${result.distanceText} • ${result.durationText}"
                startNavigation(destinationName, destinationLatLng, result.steps, result.polyline, summary)
                _uiState.update { it.copy(statusText = "Ready") }
            } else {
                _events.send(NavEvent.VibrateError)
                _events.send(NavEvent.Speak("Could not get directions."))
                _uiState.update { it.copy(statusText = "Ready") }
            }
        }
    }

    private fun startNavigation(
            destinationName: String,
            destinationLatLng: LatLng,
            steps: List<NavStep>,
            polyline: List<LatLng>,
            routeSummary: String = ""
    ) {
        _uiState.update {
            it.copy(
                navigating = true,
                destinationName = destinationName,
                destinationLatLng = destinationLatLng,
                navSteps = steps,
                currentStepIndex = 0,
                routePolyline = polyline,
                routeSummary = routeSummary,
                instructionText = "Starting navigation to $destinationName"
            )
        }
        NavStateManager.routeSummary = routeSummary
        viewModelScope.launch {
            _events.send(NavEvent.VibrateSuccess)
            _events.send(NavEvent.Speak("Starting navigation to $destinationName"))
            if (steps.isNotEmpty()) {
                _events.send(NavEvent.SpeakNavigation(steps[0].instruction))
            }
        }
    }

    fun stopNavigation() {
        _uiState.update {
            it.copy(
                navigating = false,
                destinationName = null,
                destinationLatLng = null,
                navSteps = emptyList(),
                currentStepIndex = 0,
                routePolyline = null,
                instructionText = "Navigation stopped"
            )
        }
        viewModelScope.launch {
            _events.send(NavEvent.Speak("Navigation stopped"))
        }
    }

    fun setActiveUserGoal(goal: String?) {
        _uiState.update { it.copy(activeUserGoal = goal) }
        if (goal != null) {
            userActionTrigger.tryEmit(Unit)
        }
    }

    fun updateStatus(status: String) {
        _uiState.update { it.copy(statusText = status) }
    }



    fun updateInstruction(instruction: String) {
        _uiState.update { it.copy(instructionText = instruction) }
    }

    private fun checkNavigationProgress(currentLocation: Location) {
        val state = _uiState.value
        if (state.navSteps.isEmpty() || state.currentStepIndex >= state.navSteps.size) return

        val currentStep = state.navSteps[state.currentStepIndex]
        val currentLatLng = LatLng(currentLocation.latitude, currentLocation.longitude)

        // Calculate distance to the end of the current step
        val distanceToStepEnd = distanceBetween(currentLatLng, currentStep.end)
        val bearingToNext = bearingTo(currentStep.start, currentStep.end)

        _uiState.update { 
            it.copy(
                remainingDistance = distanceToStepEnd,
                routeBearing = bearingToNext
            )
        }

        if (distanceToStepEnd < 20) {
            val nextIndex = state.currentStepIndex + 1
            if (nextIndex < state.navSteps.size) {
                val nextStep = state.navSteps[nextIndex]
                _uiState.update { it.copy(currentStepIndex = nextIndex, instructionText = nextStep.instruction) }
                viewModelScope.launch {
                    _events.send(NavEvent.SpeakNavigation("Now, ${nextStep.instruction}"))
                }
            } else {
                _uiState.update { it.copy(instructionText = "You have arrived at your destination") }
                viewModelScope.launch {
                    _events.send(NavEvent.Speak("You have arrived at your destination"))
                }
                stopNavigation()
            }
        }
    }

    private fun distanceBetween(point1: LatLng, point2: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            point1.latitude, point1.longitude,
            point2.latitude, point2.longitude,
            results
        )
        return results[0]
    }

    private fun bearingTo(start: LatLng, end: LatLng): Float {
        val loc1 = Location("").apply { latitude = start.latitude; longitude = start.longitude }
        val loc2 = Location("").apply { latitude = end.latitude; longitude = end.longitude }
        return ((loc1.bearingTo(loc2) + 360) % 360)
    }

    private fun bearingDelta(b1: Float, b2: Float): Float {
        var delta = Math.abs(b1 - b2)
        if (delta > 180f) delta = 360f - delta
        return delta
    }

    private fun getBearingDescription(bearing: Float): String {
        return when {
            bearing < 22.5  || bearing >= 337.5 -> "North"
            bearing < 67.5  -> "Northeast"
            bearing < 112.5 -> "East"
            bearing < 157.5 -> "Southeast"
            bearing < 202.5 -> "South"
            bearing < 247.5 -> "Southwest"
            bearing < 292.5 -> "West"
            bearing < 337.5 -> "Northwest"
            else -> "Unknown"
        }
    }

    private fun buildNavContext(): String {
        val state = _uiState.value
        val isObstacleInFront = NavStateManager.isObstacleInFront

        if (!state.navigating) {
            return NavContextBuilder.formatNavigationBlock(
                    navigating = false,
                    routeBearing = state.routeBearing,
                    compassBearing = state.compassBearing,
                    gpsBearing = state.gpsBearing,
                    hasValidGpsBearing = state.hasValidGpsBearing,
                    instructionText = state.instructionText,
                    remainingDistanceText = "n/a",
                    routeSummary = state.routeSummary,
                    isObstacleInFront = isObstacleInFront,
                    travelVerdict = "inactive",
                    cameraVerdict = "inactive"
            )
        }

        val instruction = state.instructionText
        val distance = "${state.remainingDistance.toInt()} meters"
        val routeDir = NavContextBuilder.bearingDescription(state.routeBearing)

        val travelBearing = if (state.hasValidGpsBearing) state.gpsBearing else state.compassBearing
        val travelDir = NavContextBuilder.bearingDescription(travelBearing)
        val travelDelta = NavContextBuilder.bearingDelta(travelBearing, state.routeBearing)

        val rawLevel =
                when {
                    travelDelta > 120f -> 4
                    travelDelta > 90f -> 3
                    travelDelta > 45f -> 2
                    travelDelta > 20f -> 1
                    else -> 0
                }

        val now = System.currentTimeMillis()
        val displayLevel =
                if (rawLevel > committedVerdictLevel) {
                    if (rawLevel != pendingVerdictLevel) {
                        pendingVerdictLevel = rawLevel
                        pendingVerdictSince = now
                    }
                    if (now - pendingVerdictSince >= 2000L) {
                        committedVerdictLevel = rawLevel
                        rawLevel
                    } else {
                        committedVerdictLevel
                    }
                } else {
                    committedVerdictLevel = rawLevel
                    pendingVerdictLevel = rawLevel
                    pendingVerdictSince = now
                    rawLevel
                }

        val travelVerdict =
                when (displayLevel) {
                    4 ->
                            "⚠ WRONG WAY — heading $travelDir, nearly opposite to route (${travelDelta.toInt()}° off). Turn around."
                    3 ->
                            "⚠ HEADING AWAY — travelling $travelDir (${travelDelta.toInt()}° off-route). Turn toward $routeDir."
                    2 -> "OFF ROUTE — heading $travelDir (${travelDelta.toInt()}° off). Bear toward $routeDir."
                    1 -> "SLIGHTLY OFF — heading $travelDir (${travelDelta.toInt()}° off-route)."
                    else -> "ON TRACK — heading $travelDir."
                }

        val facingDelta = NavContextBuilder.bearingDelta(state.compassBearing, state.routeBearing)
        val cameraDir = NavContextBuilder.bearingDescription(state.compassBearing)
        val cameraVerdict =
                when {
                    facingDelta > 90f ->
                            "MISALIGNED — camera facing $cameraDir (${facingDelta.toInt()}° off route)."
                    facingDelta > 30f ->
                            "SLIGHTLY OFF — camera facing $cameraDir (${facingDelta.toInt()}° off route)."
                    else -> "ALIGNED — camera facing $cameraDir."
                }

        return NavContextBuilder.formatNavigationBlock(
                navigating = true,
                routeBearing = state.routeBearing,
                compassBearing = state.compassBearing,
                gpsBearing = state.gpsBearing,
                hasValidGpsBearing = state.hasValidGpsBearing,
                instructionText = instruction,
                remainingDistanceText = distance,
                routeSummary = state.routeSummary.ifBlank { NavStateManager.routeSummary },
                isObstacleInFront = isObstacleInFront,
                travelVerdict = travelVerdict,
                cameraVerdict = cameraVerdict
        )
    }
}

class NavigationViewModelFactory(
    private val locationRepository: LocationRepository,
    private val sensorRepository: SensorRepository,
    private val visionRepository: VisionRepository,
    private val geminiManager: GeminiManager,
    private val routeRepository: com.impairedvision.guideglass.data.RouteRepository,
    private val directionsApiKey: String
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NavigationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NavigationViewModel(locationRepository, sensorRepository, visionRepository, geminiManager, routeRepository, directionsApiKey) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
