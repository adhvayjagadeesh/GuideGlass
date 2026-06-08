package com.impairedvision.guideglass.vision

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.android.material.snackbar.Snackbar
import com.impairedvision.guideglass.R
import com.impairedvision.guideglass.util.GeocoderHelper
import com.impairedvision.guideglass.util.VibrationHelper
import com.impairedvision.guideglass.maps.Leg
import com.impairedvision.guideglass.maps.NavStateManager
import com.impairedvision.guideglass.maps.PolylineDecoder
import com.impairedvision.guideglass.maps.RetrofitClients
import com.impairedvision.guideglass.tts.SpeechHelper
import com.impairedvision.guideglass.ai.GeminiManager
import com.impairedvision.guideglass.ai.NavContextBuilder
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

class VisionActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val TAG = "VisionActivity"
        private const val THROTTLE_MS = 1500L
        private const val CAMERA_PERMISSION_CODE = 10

        // Reflex STOP rate-limiting: at most one urgent alert per cooldown, and only after the
        // previous obstacle truly cleared and re-armed. Prevents "STOP STOP STOP" flicker spam.
        private const val STOP_COOLDOWN_MS = 3500L
        private const val REARM_CLEAR_MS = 1200L
    }

    // ===== VISION UI (vision-only mode) =====
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var lastInstructionText: TextView
    private lateinit var btnToggleAnalysis: Button
    private lateinit var visionControls: View
    private lateinit var visionOnlyContainer: View

    // ===== COMBINED MODE UI =====
    private var combinedContainer: View? = null
    private var previewViewCombined: PreviewView? = null
    private var lastInstructionCombined: TextView? = null
    private var statusCombined: TextView? = null

    // ===== MAP UI (combined mode) =====
    private var googleMap: GoogleMap? = null
    private var searchDestinationView: TextView? = null
    private var navPanel: View? = null
    private var navSummary: TextView? = null
    private var stepsRecycler: RecyclerView? = null
    private var btnStartNavigation: Button? = null

    // ===== MAP STATE =====
    private var originMarker: Marker? = null
    private var destMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var navMarker: Marker? = null
    private var navigating = false
    private var navigationLocationCallback: LocationCallback? = null
    private var currentStepIndex = 0
    private var navSteps: List<NavStep> = emptyList()
    private var lastKnownLocation: LatLng? = null

    // ===== SHARED =====
    private lateinit var speechHelper: SpeechHelper
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val reflexExecutor =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "reflex-thread").apply { priority = Thread.MAX_PRIORITY }
            }
    private val uiScope = CoroutineScope(Job() + Dispatchers.Main)
    private val obstacleDetector = ObstacleDetector()

    // ===== VISION STATE =====
    private var lastAnalysisTime = 0L
    private var analysisEnabled = true
    private var combinedMode = false
    /** Single gate for Tier-2 Gemini work (replaces legacy isProcessing + geminiInFlight). */
    private val geminiInFlight = AtomicBoolean(false)

    // ===== REFLEX ALERT RATE-LIMIT STATE =====
    @Volatile private var lastStopAlertMs = 0L
    @Volatile private var lastObstacleClearMs = 0L
    @Volatile private var obstacleWasCleared = true

    // ===== ROUTE BEARING STATE =====
    private var routeBearing: Float = 0f
    private var hasValidGpsBearing = false
    private var lastWrongDirectionAlertTime = 0L
    private val WRONG_DIRECTION_THROTTLE_MS = 10_000L

    // ===== VOICE STATE =====
    private var activeUserGoal: String? = null

    // ===== SENSOR STATE =====
    private lateinit var sensorManager: android.hardware.SensorManager
    private var mAccel = 0f
    private var mAccelCurrent = android.hardware.SensorManager.GRAVITY_EARTH
    private var mAccelLast = android.hardware.SensorManager.GRAVITY_EARTH
    private var isMoving = false

    // ===== COMPASS / BEARING STATE =====
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private var compassBearing: Float = 0f
    private var gpsBearing: Float = 0f

    // ===== GEMINI =====
    private lateinit var geminiManager: GeminiManager

    // ===== PERMISSION LAUNCHERS =====
    private val locationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms
                ->
                val granted =
                        perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                if (granted) {
                    enableMyLocation()
                    centerOnLastKnownLocation()
                } else {
                    speechHelper.speak("Location permission is required for map features.")
                    Toast.makeText(this, "Location permission denied", Toast.LENGTH_LONG).show()
                }
            }

    private val autocompleteLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    val place = Autocomplete.getPlaceFromIntent(result.data!!)
                    val destLatLng = place.latLng
                    if (destLatLng != null) {
                        searchDestinationView?.text = place.name ?: place.address ?: "Destination"

                        destMarker?.remove()
                        destMarker =
                                googleMap?.addMarker(
                                        MarkerOptions()
                                                .position(destLatLng)
                                                .title(place.name ?: "Destination")
                                                .icon(
                                                        BitmapDescriptorFactory.defaultMarker(
                                                                BitmapDescriptorFactory.HUE_RED
                                                        )
                                                )
                                )

                        if (originMarker == null && lastKnownLocation != null) {
                            originMarker =
                                    googleMap?.addMarker(
                                            MarkerOptions()
                                                    .position(lastKnownLocation!!)
                                                    .title("Origin")
                                                    .icon(
                                                            BitmapDescriptorFactory.defaultMarker(
                                                                    BitmapDescriptorFactory
                                                                            .HUE_GREEN
                                                            )
                                                    )
                                    )
                        }

                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(destLatLng, 14f))
                        speechHelper.speak(
                                "Destination set to ${place.name ?: "selected location"}"
                        )
                        fetchAndDrawRoute()
                    }
                } else if (result.resultCode != Activity.RESULT_CANCELED) {
                    result.data?.let {
                        val status = Autocomplete.getStatusFromIntent(it)
                        Snackbar.make(
                                        findViewById(android.R.id.content),
                                        "Search error: ${status.statusMessage}",
                                        Snackbar.LENGTH_LONG
                                )
                                .show()
                    }
                }
            }

    // ========================================
    //               LIFECYCLE
    // ========================================

    // Handles both shake detection (existing) AND compass updates
    private val sensorListener =
            object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent) {
                    when (event.sensor.type) {
                        android.hardware.Sensor.TYPE_ACCELEROMETER -> {
                            System.arraycopy(event.values, 0, accelerometerReading, 0, 3)

                            val x = event.values[0]
                            val y = event.values[1]
                            val z = event.values[2]
                            mAccelLast = mAccelCurrent
                            mAccelCurrent = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                            val delta = mAccelCurrent - mAccelLast
                            mAccel = mAccel * 0.9f + delta
                            val movementDetected = mAccel > 0.5f
                            if (movementDetected && !isMoving) {
                                isMoving = true
                                lastAnalysisTime = 0L
                            } else if (!movementDetected) {
                                isMoving = false
                            }

                            updateCompassBearing()
                        }
                        android.hardware.Sensor.TYPE_MAGNETIC_FIELD -> {
                            System.arraycopy(event.values, 0, magnetometerReading, 0, 3)
                            updateCompassBearing()
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vision)

        combinedMode = intent.getBooleanExtra("COMBINED_MODE", false)
        speechHelper = SpeechHelper(this)
        geminiManager = GeminiManager(getString(R.string.gemini_api_key))

        // ----- Vision-only UI (always in layout) -----
        previewView = findViewById(R.id.previewView)
        visionOnlyContainer = findViewById(R.id.vision_only_container)
        visionControls = findViewById(R.id.vision_controls)
        statusText = findViewById(R.id.tv_status)
        lastInstructionText = findViewById(R.id.tv_last_instruction)
        btnToggleAnalysis = findViewById(R.id.btn_toggle_analysis)

        navPanel = findViewById(R.id.nav_panel)
        navSummary = findViewById(R.id.tv_nav_summary)
        btnStartNavigation = findViewById(R.id.btn_start_navigation)
        stepsRecycler =
                findViewById<RecyclerView>(R.id.rv_steps)?.also {
                    it.layoutManager = LinearLayoutManager(this)
                }

        btnToggleAnalysis.setOnClickListener {
            analysisEnabled = !analysisEnabled
            updateAnalysisButton()
            val status =
                    if (analysisEnabled) "Vision analysis enabled" else "Vision analysis paused"
            speechHelper.speak(status)
            updateStatusText(status)
        }

        btnStartNavigation?.setOnClickListener {
            if (navigating) {
                clearRoute()
                speechHelper.speak("Navigation stopped")
            } else {
                startInAppNavigation()
            }
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
        sensorManager.registerListener(
                sensorListener,
                sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER),
                android.hardware.SensorManager.SENSOR_DELAY_UI
        )
        sensorManager.registerListener(
                sensorListener,
                sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD),
                android.hardware.SensorManager.SENSOR_DELAY_UI
        )

        if (combinedMode) {
            visionOnlyContainer.visibility = View.GONE
            visionControls.visibility = View.GONE
            setupCombinedMode()
        }

        scheduleCameraStart()

        val welcomeMsg =
                if (combinedMode) {
                    "Combined mode active. Map and vision are both running. Search for a destination to begin navigation."
                } else {
                    "Vision mode started. Point camera forward."
                }
        previewView.post { speechHelper.speak(welcomeMsg) }

        previewView.setOnLongClickListener {
            startVoiceCommand()
            true
        }

        previewViewCombined?.setOnLongClickListener {
            startVoiceCommand()
            true
        }
    }

    // ========================================
    //           COMBINED MODE SETUP
    // ========================================

    private fun setupCombinedMode() {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        if (ContextCompat.checkSelfPermission(this, fine) != PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(this, coarse) !=
                                PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(arrayOf(fine, coarse))
        }

        combinedContainer =
                findViewById<View>(R.id.combined_container).also { it.visibility = View.VISIBLE }

        previewViewCombined = findViewById(R.id.previewViewCombined)
        lastInstructionCombined = findViewById(R.id.tv_last_instruction_combined)
        statusCombined = findViewById(R.id.tv_status_combined)

        searchDestinationView = findViewById(R.id.tv_search_destination)
        searchDestinationView?.setOnClickListener { openDestinationSearch() }

        val mapFragment =
                supportFragmentManager.findFragmentById(R.id.combined_map_fragment) as?
                        SupportMapFragment

        lifecycleScope.launch(Dispatchers.Default) {
            if (!Places.isInitialized()) {
                Places.initialize(applicationContext, getString(R.string.google_maps_key))
            }
            withContext(Dispatchers.Main) { mapFragment?.getMapAsync(this@VisionActivity) }
        }
    }

    /** Defer camera bind until PreviewView has been laid out (fixes combined-mode startup hang). */
    private fun scheduleCameraStart() {
        val previewHost =
                if (combinedMode) previewViewCombined ?: previewView else previewView
        previewHost.post { checkCameraPermissionAndStart() }
    }

    // ========================================
    //             MAP CALLBACKS
    // ========================================

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = true

        val start = LatLng(37.7749, -122.4194)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 12f))

        map.setOnMapLongClickListener { latLng ->
            when {
                originMarker == null -> {
                    clearRoute()
                    originMarker =
                            map.addMarker(
                                    MarkerOptions()
                                            .position(latLng)
                                            .title("Origin")
                                            .icon(
                                                    BitmapDescriptorFactory.defaultMarker(
                                                            BitmapDescriptorFactory.HUE_GREEN
                                                    )
                                            )
                            )
                    Toast.makeText(this, "Origin set.", Toast.LENGTH_SHORT).show()
                    speechHelper.speak("Origin set")
                }
                destMarker == null -> {
                    destMarker =
                            map.addMarker(
                                    MarkerOptions()
                                            .position(latLng)
                                            .title("Destination")
                                            .icon(
                                                    BitmapDescriptorFactory.defaultMarker(
                                                            BitmapDescriptorFactory.HUE_RED
                                                    )
                                            )
                            )
                    speechHelper.speak("Destination set. Getting directions.")
                    fetchAndDrawRoute()
                }
                else -> {
                    clearRoute()
                    originMarker =
                            map.addMarker(
                                    MarkerOptions()
                                            .position(latLng)
                                            .title("Origin")
                                            .icon(
                                                    BitmapDescriptorFactory.defaultMarker(
                                                            BitmapDescriptorFactory.HUE_GREEN
                                                    )
                                            )
                            )
                    Toast.makeText(this, "Origin reset.", Toast.LENGTH_SHORT).show()
                    speechHelper.speak("Origin reset")
                }
            }
        }

        map.setOnPoiClickListener { poi ->
            map.addMarker(MarkerOptions().position(poi.latLng).title(poi.name))?.showInfoWindow()
            speechHelper.speak("Point of interest: ${poi.name}")
        }

        enableMyLocation()
        centerOnLastKnownLocation()
    }

    // ========================================
    //           MAP NAVIGATION
    // ========================================

    private fun openDestinationSearch() {
        val fields =
                listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS)
        val intent =
                Autocomplete.IntentBuilder(AutocompleteActivityMode.OVERLAY, fields).build(this)
        autocompleteLauncher.launch(intent)
    }

    private fun clearRoute() {
        stopInAppNavigation()
        btnStartNavigation?.text = "Start Navigation"

        routePolyline?.remove()
        routePolyline = null
        navMarker?.remove()
        navMarker = null
        originMarker?.remove()
        originMarker = null
        destMarker?.remove()
        destMarker = null

        navPanel?.visibility = View.GONE
        navSteps = emptyList()
        currentStepIndex = 0
        NavStateManager.routeSummary = ""
        NavStateManager.isObstacleInFront = false

        lastKnownLocation?.let { loc ->
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 14f))
        }
    }

    private fun fetchAndDrawRoute() {
        val map = googleMap ?: return
        val origin =
                originMarker?.position
                        ?: lastKnownLocation
                                ?: run {
                            speechHelper.speak("Waiting for GPS signal...")
                            return
                        }
        val dest = destMarker?.position ?: return

        val originStr = "${origin.latitude},${origin.longitude}"
        val destStr = "${dest.latitude},${dest.longitude}"
        val apiKey = getString(R.string.google_directions_key)

        uiScope.launch {
            try {
                val resp =
                        withContext(Dispatchers.IO) {
                            RetrofitClients.directions.directions(
                                    origin = originStr,
                                    destination = destStr,
                                    mode = "walking",
                                    key = apiKey
                            )
                        }

                if (resp.status != "OK" || resp.routes.isEmpty()) {
                    Snackbar.make(
                                    findViewById(android.R.id.content),
                                    "Directions error: ${resp.status}",
                                    Snackbar.LENGTH_LONG
                            )
                            .show()
                    speechHelper.speak(
                            "Could not get directions. ${resp.error_message ?: resp.status}"
                    )
                    return@launch
                }

                val route = resp.routes.first()
                val points = PolylineDecoder.decode(route.overview_polyline.points)

                withContext(Dispatchers.Main) {
                    routePolyline?.remove()
                    routePolyline =
                            map.addPolyline(
                                    PolylineOptions()
                                            .addAll(points)
                                            .width(12f)
                                            .color(0xFF1E88E5.toInt())
                                            .geodesic(true)
                            )
                }

                val bounds = LatLngBounds.builder()
                points.forEach { bounds.include(it) }
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100))

                val leg = route.legs.firstOrNull()
                if (leg != null) {
                    showNavigationPanel(leg)
                    startInAppNavigation()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching directions", e)
                Snackbar.make(
                                findViewById(android.R.id.content),
                                "Error: ${e.message}",
                                Snackbar.LENGTH_LONG
                        )
                        .show()
                speechHelper.speak("Error getting directions")
            }
        }
    }

    data class NavStep(
            val instruction: String,
            val distance: String,
            val start: LatLng,
            val end: LatLng
    )

    private fun showNavigationPanel(leg: Leg) {
        val summary = "${leg.distance.text} • ${leg.duration.text}"
        NavStateManager.routeSummary = summary
        navSummary?.text = "Distance: $summary"
        speechHelper.speak("Route found. ${leg.distance.text}, about ${leg.duration.text}")

        navSteps =
                leg.steps.map { step ->
                    val instr =
                            HtmlCompat.fromHtml(
                                            step.htmlInstructions ?: "",
                                            HtmlCompat.FROM_HTML_MODE_LEGACY
                                    )
                                    .toString()

                    NavStep(
                            instruction = instr,
                            distance = step.distance.text,
                            start = LatLng(step.startLocation.lat, step.startLocation.lng),
                            end = LatLng(step.endLocation.lat, step.endLocation.lng)
                    )
                }

        if (navSteps.isNotEmpty()) {
            speechHelper.speakNavigation("First, ${navSteps.first().instruction}")
        }

        val adapter =
                StepsAdapter(navSteps) { step, _ ->
                    googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(step.start, 16f))
                    navMarker?.remove()
                    navMarker =
                            googleMap?.addMarker(
                                    MarkerOptions().position(step.start).title(step.instruction)
                            )
                    navMarker?.showInfoWindow()
                    speechHelper.speakNavigation("${step.instruction}. ${step.distance}")
                }

        stepsRecycler?.adapter = adapter
        navPanel?.visibility = View.VISIBLE
        btnStartNavigation?.text = "Stop Navigation"
    }

    private inner class StepsAdapter(
            private val steps: List<NavStep>,
            private val onStepClick: (NavStep, Int) -> Unit
    ) : RecyclerView.Adapter<StepsAdapter.StepViewHolder>() {

        inner class StepViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(android.R.id.text1)
            val subtitle: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
            val itemView =
                    layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            return StepViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
            val step = steps[position]
            holder.title.text = step.instruction
            holder.subtitle.text = step.distance
            holder.itemView.setOnClickListener { onStepClick(step, position) }
        }

        override fun getItemCount() = steps.size
    }

    // ========================================
    //          IN-APP NAVIGATION
    // ========================================

    private fun startInAppNavigation() {
        if (routePolyline == null || navSteps.isEmpty()) {
            Toast.makeText(this, "Get directions first.", Toast.LENGTH_SHORT).show()
            speechHelper.speak("Please set a destination first")
            return
        }

        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        val hasFine =
                ContextCompat.checkSelfPermission(this, fine) == PackageManager.PERMISSION_GRANTED
        val hasCoarse =
                ContextCompat.checkSelfPermission(this, coarse) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            speechHelper.speak("Location permission required")
            return
        }

        val fused = LocationServices.getFusedLocationProviderClient(this)

        navigationLocationCallback =
                object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        if (!navigating) return
                        val loc = result.lastLocation ?: return
                        val here = LatLng(loc.latitude, loc.longitude)
                        lastKnownLocation = here

                        if (loc.hasBearing()) {
                            gpsBearing = loc.bearing
                            hasValidGpsBearing = true
                        }

                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(here, 17f))
                        checkNavigationProgress(here)
                    }
                }

        val request =
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                        .setMinUpdateDistanceMeters(3f)
                        .build()

        navigating = true
        currentStepIndex = 0
        lastAnalysisTime = 0L
        hasValidGpsBearing = false
        lastWrongDirectionAlertTime = 0L

        startLocationUpdates()

        try {
            fused.requestLocationUpdates(
                    request,
                    navigationLocationCallback!!,
                    Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission error", e)
        }

        btnStartNavigation?.text = "Stop Navigation"
        Toast.makeText(this, "Navigation started.", Toast.LENGTH_SHORT).show()
        speechHelper.speak("Navigation started. ${navSteps.firstOrNull()?.instruction ?: ""}")
    }

    private fun checkNavigationProgress(currentLocation: LatLng) {
        if (currentStepIndex >= navSteps.size) return

        val currentStep = navSteps[currentStepIndex]
        val distanceToStepEnd = distanceBetween(currentLocation, currentStep.end)

        NavStateManager.currentInstruction = currentStep.instruction
        NavStateManager.remainingDistance = "${distanceToStepEnd.toInt()} meters"

        // Calculate intended route bearing for the current step
        routeBearing = bearingTo(currentStep.start, currentStep.end)
        NavStateManager.routeBearing = routeBearing
        NavStateManager.currentStepIndex = currentStepIndex

        // Wrong-direction detection — only when GPS has established a real bearing
        if (hasValidGpsBearing) {
            val delta = bearingDelta(gpsBearing, routeBearing)
            val currentTime = System.currentTimeMillis()
            if (delta > 45f &&
                            currentTime - lastWrongDirectionAlertTime >= WRONG_DIRECTION_THROTTLE_MS
            ) {
                lastWrongDirectionAlertTime = currentTime
                val routeDir = getBearingDescription(routeBearing)
                val travelDir = getBearingDescription(gpsBearing)
                speechHelper.speakUrgent(
                        "Wrong direction. You are heading $travelDir, turn toward $routeDir."
                )
            }
        }

        if (distanceToStepEnd < 20) {
            currentStepIndex++
            if (currentStepIndex < navSteps.size) {
                val nextStep = navSteps[currentStepIndex]
                NavStateManager.currentInstruction = nextStep.instruction
                speechHelper.speakNavigation("Now, ${nextStep.instruction}")
            } else {
                speechHelper.speak("You have arrived at your destination")
                stopInAppNavigation()
            }
        }
    }

    private fun startLocationUpdates() {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, fine) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val request =
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                        .setMinUpdateDistanceMeters(2f)
                        .build()

        val fused = LocationServices.getFusedLocationProviderClient(this)

        try {
            fused.requestLocationUpdates(
                    request,
                    object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            val loc = result.lastLocation ?: return
                            val currentLatLng = LatLng(loc.latitude, loc.longitude)
                            lastKnownLocation = currentLatLng

                            if (navigating) {
                                checkNavigationProgress(currentLatLng)
                            }
                        }
                    },
                    Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location updates failed", e)
        }
    }

    private fun distanceBetween(point1: LatLng, point2: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
                point1.latitude,
                point1.longitude,
                point2.latitude,
                point2.longitude,
                results
        )
        return results[0]
    }

    private fun stopInAppNavigation() {
        if (!navigating) return
        navigating = false
        hasValidGpsBearing = false

        navigationLocationCallback?.let { cb ->
            val fused = LocationServices.getFusedLocationProviderClient(this)
            fused.removeLocationUpdates(cb)
        }
        navigationLocationCallback = null
    }

    // ========================================
    //              LOCATION
    // ========================================

    private fun enableMyLocation() {
        val map = googleMap ?: return
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION

        val hasFine =
                ContextCompat.checkSelfPermission(this, fine) == PackageManager.PERMISSION_GRANTED
        val hasCoarse =
                ContextCompat.checkSelfPermission(this, coarse) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            try {
                map.isMyLocationEnabled = true
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception enabling location", e)
            }
        } else {
            locationPermissionLauncher.launch(arrayOf(fine, coarse))
        }
    }

    private fun centerOnLastKnownLocation() {
        val map = googleMap ?: return
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION

        val hasLocation =
                ContextCompat.checkSelfPermission(this, fine) ==
                        PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, coarse) ==
                                PackageManager.PERMISSION_GRANTED

        if (!hasLocation) return

        try {
            val fused = LocationServices.getFusedLocationProviderClient(this)
            fused.lastLocation.addOnSuccessListener { loc ->
                loc?.let {
                    val here = LatLng(it.latitude, it.longitude)
                    lastKnownLocation = here
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(here, 14f))
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting location", e)
        }
    }

    // ========================================
    //          CAMERA & VISION
    // ========================================

    private fun checkCameraPermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
                speechHelper.speak("Camera permission is required for vision mode")
                finish()
            }
        }
    }

    private fun startVoiceCommand() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            speechHelper.speak("Microphone permission is required.")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 102)
            return
        }

        analysisEnabled = false
        geminiInFlight.set(false)
        speechHelper.stop()

        try {
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            VibrationHelper.vibrateOneShot(this, 100L)
        } catch (e: Exception) {
            Log.e(TAG, "Feedback error", e)
        }

        val voicePopup = findViewById<View>(R.id.voice_popup)
        val voiceStatus = findViewById<TextView>(R.id.tv_voice_status)
        voicePopup.visibility = View.VISIBLE
        voicePopup.alpha = 0f
        voicePopup.animate().alpha(1f).setDuration(300).start()

        val intent =
                android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .apply {
                            putExtra(
                                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        }

        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(
                object : android.speech.RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        voiceStatus.text = "Speak now..."
                    }
                    override fun onBeginningOfSpeech() {
                        voiceStatus.text = "Listening..."
                    }

                    override fun onResults(results: Bundle?) {
                        val matches =
                                results?.getStringArrayList(
                                        android.speech.SpeechRecognizer.RESULTS_RECOGNITION
                                )
                        if (!matches.isNullOrEmpty()) {
                            voiceStatus.text = matches[0]
                            handleVoiceCommand(matches[0])
                        }
                        hideVoicePopup()
                    }

                    override fun onError(error: Int) {
                        Log.e(TAG, "Speech Error Code: $error")
                        if (error != android.speech.SpeechRecognizer.ERROR_NO_MATCH) {
                            speechHelper.speak("I didn't catch that.")
                        }
                        hideVoicePopup()
                    }

                    private fun hideVoicePopup() {
                        voicePopup
                                .animate()
                                .alpha(0f)
                                .setDuration(300)
                                .withEndAction {
                                    voicePopup.visibility = View.GONE
                                    analysisEnabled = true
                                    recognizer.destroy()
                                }
                                .start()
                    }

                    override fun onEndOfSpeech() {
                        voiceStatus.text = "Processing..."
                    }
                    override fun onRmsChanged(rmsdB: Float) {
                        val progress = findViewById<ProgressBar>(R.id.voice_progress)
                        progress.progress = (rmsdB + 2).toInt() * 10
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches =
                                partialResults?.getStringArrayList(
                                        android.speech.SpeechRecognizer.RESULTS_RECOGNITION
                                )
                        if (!matches.isNullOrEmpty()) {
                            voiceStatus.text = matches[0]
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                }
        )

        recognizer.startListening(intent)
    }

    private fun handleVoiceCommand(command: String) {
        val lowerCaseCommand = command.lowercase()

        if (lowerCaseCommand.contains("nearest") || lowerCaseCommand.contains("closest")) {
            val placeTypeQuery =
                    lowerCaseCommand.substringAfter("nearest ").substringAfter("closest ").trim()
            if (placeTypeQuery.isNotBlank()) {
                speechHelper.speak("Searching for the nearest $placeTypeQuery.")
                findNearestPlaceByType(placeTypeQuery)
            }
        } else if (lowerCaseCommand.startsWith("navigate to") ||
                        lowerCaseCommand.startsWith("directions to")
        ) {
            val destinationQuery = lowerCaseCommand.substringAfter("to ").trim()
            if (destinationQuery.isNotBlank()) {
                speechHelper.speak("Getting directions to $destinationQuery.")
                geocodeAndSetDestination(destinationQuery)
            }
        } else if (lowerCaseCommand.contains("stop") || lowerCaseCommand.contains("cancel")) {
            activeUserGoal = null
            navigating = false
            stopInAppNavigation()
            clearRoute()
            speechHelper.speak("Goal cleared. All tasks stopped.")
        } else {
            activeUserGoal = command
            speechHelper.speak("Okay, searching for ${activeUserGoal}.")
        }
    }

    private fun findNearestPlaceByType(placeTypeQuery: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            speechHelper.speak("I need location permission to find nearby places.")
            return
        }

        val placeFields =
                listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.TYPES)
        val request =
                com.google.android.libraries.places.api.net.FindCurrentPlaceRequest.newInstance(
                        placeFields
                )
        val placesClient = Places.createClient(this)

        placesClient
                .findCurrentPlace(request)
                .addOnSuccessListener { response ->
                    val bestMatch =
                            response.placeLikelihoods
                                    .filter { placeLikelihood ->
                                        placeLikelihood.place.placeTypes?.any { type ->
                                            type.toString().lowercase().contains(placeTypeQuery)
                                        } == true
                                    }
                                    .ifEmpty {
                                        response.placeLikelihoods.filter {
                                            it.place.name?.lowercase()?.contains(placeTypeQuery) ==
                                                    true
                                        }
                                    }
                                    .maxByOrNull { it.likelihood }

                    if (bestMatch != null) {
                        val foundPlace = bestMatch.place
                        speechHelper.speak("Found ${foundPlace.name}. Starting navigation.")
                        runOnUiThread {
                            if (originMarker == null && lastKnownLocation != null) {
                                originMarker =
                                        googleMap?.addMarker(
                                                MarkerOptions()
                                                        .position(lastKnownLocation!!)
                                                        .title("My Location")
                                        )
                            }

                            destMarker?.remove()
                            destMarker =
                                    googleMap?.addMarker(
                                            MarkerOptions()
                                                    .position(foundPlace.latLng!!)
                                                    .title(foundPlace.name)
                                                    .icon(
                                                            BitmapDescriptorFactory.defaultMarker(
                                                                    BitmapDescriptorFactory.HUE_RED
                                                            )
                                                    )
                                    )
                            googleMap?.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(foundPlace.latLng!!, 15f)
                            )
                            fetchAndDrawRoute()
                        }
                    } else {
                        speechHelper.speak("Sorry, I couldn't find a $placeTypeQuery nearby.")
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Places API call failed", exception)
                    speechHelper.speak("There was an error searching for places.")
                }
    }

    private fun geocodeAndSetDestination(query: String) {
        lifecycleScope.launch {
            try {
                val addresses = GeocoderHelper.getAddressesFromLocationName(this@VisionActivity, query, 1)
                if (addresses?.isNotEmpty() == true) {
                    val location = addresses[0]
                    val destination = LatLng(location.latitude, location.longitude)
                    withContext(Dispatchers.Main) {
                        if (originMarker == null && lastKnownLocation != null) {
                            originMarker =
                                    googleMap?.addMarker(
                                            MarkerOptions()
                                                    .position(lastKnownLocation!!)
                                                    .title("My Location")
                                                    .icon(
                                                            BitmapDescriptorFactory.defaultMarker(
                                                                    BitmapDescriptorFactory
                                                                            .HUE_GREEN
                                                            )
                                                    )
                                    )
                        }

                        destMarker?.remove()
                        destMarker =
                                googleMap?.addMarker(
                                        MarkerOptions()
                                                .position(destination)
                                                .title(query)
                                                .icon(
                                                        BitmapDescriptorFactory.defaultMarker(
                                                                BitmapDescriptorFactory.HUE_RED
                                                        )
                                                )
                                )
                        googleMap?.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(destination, 14f)
                        )
                        fetchAndDrawRoute()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        speechHelper.speak("Sorry, I could not find a location for $query.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Geocoding failed", e)
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val targetPreview = if (combinedMode) previewViewCombined!! else previewView

                    val preview =
                            Preview.Builder().build().also {
                                it.setSurfaceProvider(targetPreview.surfaceProvider)
                            }

                    val imageAnalysis =
                            ImageAnalysis.Builder()
                                    .setBackpressureStrategy(
                                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                    )
                                    .setOutputImageFormat(
                                            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                                    )
                                    .build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val bitmap = imageProxyToBitmap(imageProxy)
                        imageProxy.close()
                        if (bitmap != null) {
                            val timestamp = System.currentTimeMillis()
                            reflexExecutor.execute { processFrame(bitmap, timestamp) }
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                                this,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                        )
                        updateStatusText("Camera ready")
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera binding failed", e)
                        updateStatusText("Camera error")
                        speechHelper.speak("Camera initialization failed")
                    }
                },
                ContextCompat.getMainExecutor(this)
        )
    }

    private fun processFrame(bitmap: Bitmap, currentTime: Long) {
        obstacleDetector.processFrame(
                bitmap,
                object : ObstacleDetector.Listener {
                    override fun onObstacleEntered(decision: ObstacleDetector.ReflexDecision) {
                        // Persistent flag: the reflex fires STOP at most once per cooldown here,
                        // then Gemini (and the local hint) take over while the flag stays set.
                        NavStateManager.isObstacleInFront = true

                        val now = System.currentTimeMillis()
                        val cooledDown = now - lastStopAlertMs >= STOP_COOLDOWN_MS
                        val rearmed = obstacleWasCleared && now - lastObstacleClearMs >= REARM_CLEAR_MS
                        if (cooledDown && rearmed) {
                            lastStopAlertMs = now
                            obstacleWasCleared = false
                            runOnUiThread { fireReflexStopAlert(decision) }
                        }

                        // Best-effort fresh, obstacle-aware Gemini guidance (gated normally).
                        maybeLaunchGemini(bitmap, currentTime, bypassThrottle = true)
                    }

                    override fun onObstacleCleared() {
                        NavStateManager.isObstacleInFront = false
                        obstacleWasCleared = true
                        lastObstacleClearMs = System.currentTimeMillis()
                    }
                }
        )

        maybeLaunchGemini(bitmap, currentTime, bypassThrottle = false)
    }

    private fun fireReflexStopAlert(decision: ObstacleDetector.ReflexDecision) {
        // Immediate, network-independent guidance: speak a conservative veer hint when the reflex
        // is confident a side is clear. Otherwise just STOP — Gemini may refine guidance shortly.
        val alert = when (decision.avoidance) {
            ObstacleDetector.AvoidanceHint.VEER_LEFT -> "Stop. Obstacle ahead. Veer left."
            ObstacleDetector.AvoidanceHint.VEER_RIGHT -> "Stop. Obstacle ahead. Veer right."
            ObstacleDetector.AvoidanceHint.NONE -> "Stop. Obstacle ahead."
        }
        speechHelper.speakUrgent(alert)
        updateStatusText("OBSTACLE ALERT")
        VibrationHelper.vibrateOneShot(this, 200L)
    }

    /**
     * Tier 2 Gemini — gated so in-flight reasoning cannot be interrupted by frame triggers.
     */
    private fun maybeLaunchGemini(bitmap: Bitmap, currentTime: Long, bypassThrottle: Boolean) {
        if (!analysisEnabled || geminiInFlight.get()) return

        val dynamicThrottle = if (isMoving || activeUserGoal != null) THROTTLE_MS else 3000L
        if (!bypassThrottle && currentTime - lastAnalysisTime < dynamicThrottle) return
        if (!geminiInFlight.compareAndSet(false, true)) return

        lastAnalysisTime = currentTime
        runOnUiThread {
            updateStatusText(if (activeUserGoal != null) "Thinking..." else "Analyzing...")
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                analyzeWithGemini(bitmap)
            } finally {
                geminiInFlight.set(false)
                runOnUiThread {
                    updateStatusText(if (analysisEnabled) "Active" else "Paused")
                }
            }
        }
    }

    private suspend fun analyzeWithGemini(originalBitmap: Bitmap) {
        try {
            val navCtx = buildNavContext()
            val userGoal = activeUserGoal

            // GeminiManager emits cumulative chunks; speak only once when the stream completes.
            var finalResponse = ""
            geminiManager.analyzeWithGeminiStream(
                            originalBitmap,
                            navCtx,
                            userGoal,
                            NavStateManager.isObstacleInFront
                    )
                    .collect { cumulativeChunk -> finalResponse = cumulativeChunk }

            if (finalResponse.isNotBlank()) {
                handleGeminiFinalResponse(finalResponse)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API Error: ${e.message}")
            runOnUiThread { updateStatusText("Analysis error") }
        }
    }

    private fun handleGeminiFinalResponse(resultText: String) {
        val cleanText = resultText.replace("[DONE]", "").trim()

        runOnUiThread {
            Log.d(TAG, "Gemini (Stream) Full Response: $resultText")
            updateInstructionText(cleanText)
            updateStatusText("Ready")

            val obstaclePresent = NavStateManager.isObstacleInFront
            val saysStop = cleanText.contains("STOP", ignoreCase = true)
            val saysClear = isPathClearMessage(cleanText)

            // While an obstacle is active, only block the two responses that contradict the reflex:
            // a duplicate STOP, and reassuring "path clear / walk forward". Directional avoidance
            // guidance ("veer left", "step right", "turn around") is allowed through. Once the
            // obstacle clears, even "path clear" is valid and spoken normally.
            when {
                obstaclePresent && saysStop ->
                        Log.d(TAG, "Suppressing Gemini STOP — reflex owns urgent alert")
                obstaclePresent && saysClear ->
                        Log.d(TAG, "Suppressing 'clear' guidance — obstacle still in front")
                else -> speechHelper.speak(cleanText)
            }

            if (resultText.contains("[DONE]", ignoreCase = true)) {
                activeUserGoal = null
                VibrationHelper.vibrateWaveform(
                        this,
                        longArrayOf(0, 120, 80, 120),
                        intArrayOf(0, 200, 0, 200)
                )
                Log.d(TAG, "Task completed and goal cleared automatically.")
            }
        }
    }

    /** Heuristic: does Gemini's text reassure the path is open (contradicts an active reflex)? */
    private fun isPathClearMessage(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("path is clear") ||
                t.contains("path clear") ||
                t.contains("walk forward") ||
                t.contains("clear to") ||
                t.contains("all clear")
    }

    // ========================================
    //        COMPASS HELPERS
    // ========================================

    private fun updateCompassBearing() {
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        val success =
                android.hardware.SensorManager.getRotationMatrix(
                        rotationMatrix,
                        null,
                        accelerometerReading,
                        magnetometerReading
                )
        if (success) {
            android.hardware.SensorManager.getOrientation(rotationMatrix, orientationAngles)
            compassBearing =
                    ((Math.toDegrees(orientationAngles[0].toDouble()) + 360) % 360).toFloat()
        }
    }

    private fun getBearingDescription(bearing: Float): String {
        return when {
            bearing < 22.5 || bearing >= 337.5 -> "North"
            bearing < 67.5 -> "North-East"
            bearing < 112.5 -> "East"
            bearing < 157.5 -> "South-East"
            bearing < 202.5 -> "South"
            bearing < 247.5 -> "South-West"
            bearing < 292.5 -> "West"
            else -> "North-West"
        }
    }

    /**
     * Calculates the initial bearing from [from] to [to] using the forward azimuth formula. Returns
     * degrees 0-360 (0 = North, 90 = East, 180 = South, 270 = West).
     */
    private fun bearingTo(from: LatLng, to: LatLng): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)
        val x = Math.sin(dLng) * Math.cos(lat2)
        val y = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng)
        return ((Math.toDegrees(Math.atan2(x, y)) + 360) % 360).toFloat()
    }

    /**
     * Returns the smallest angular difference between two bearings (result is 0–180). Correctly
     * handles the 0°/360° wrap-around.
     */
    private fun bearingDelta(a: Float, b: Float): Float {
        val diff = Math.abs(a - b) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    private fun buildNavContext(): String {
        if (!navigating) {
            return NavContextBuilder.formatNavigationBlock(
                    navigating = false,
                    routeBearing = routeBearing,
                    compassBearing = compassBearing,
                    gpsBearing = gpsBearing,
                    hasValidGpsBearing = hasValidGpsBearing,
                    instructionText = NavStateManager.currentInstruction,
                    remainingDistanceText = NavStateManager.remainingDistance,
                    routeSummary = NavStateManager.routeSummary,
                    isObstacleInFront = NavStateManager.isObstacleInFront,
                    travelVerdict = "inactive",
                    cameraVerdict = "inactive"
            )
        }

        val routeDir = NavContextBuilder.bearingDescription(routeBearing)
        val instruction = NavStateManager.currentInstruction
        val distance = NavStateManager.remainingDistance

        val travelBearing = if (hasValidGpsBearing) gpsBearing else compassBearing
        val travelDelta = NavContextBuilder.bearingDelta(travelBearing, routeBearing)
        val travelVerdict =
                when {
                    travelDelta > 120f ->
                            "⚠ WRONG WAY — heading ${NavContextBuilder.bearingDescription(travelBearing)} (${travelDelta.toInt()}° off). Turn around."
                    travelDelta > 90f ->
                            "⚠ HEADING AWAY — travelling ${NavContextBuilder.bearingDescription(travelBearing)} (${travelDelta.toInt()}° off-route). Turn toward $routeDir."
                    travelDelta > 45f ->
                            "OFF ROUTE — heading ${NavContextBuilder.bearingDescription(travelBearing)} (${travelDelta.toInt()}° off). Bear toward $routeDir."
                    travelDelta > 20f ->
                            "SLIGHTLY OFF — heading ${NavContextBuilder.bearingDescription(travelBearing)} (${travelDelta.toInt()}° off-route)."
                    else ->
                            "ON TRACK — heading ${NavContextBuilder.bearingDescription(travelBearing)}."
                }

        val facingDelta = NavContextBuilder.bearingDelta(compassBearing, routeBearing)
        val cameraDir = NavContextBuilder.bearingDescription(compassBearing)
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
                routeBearing = routeBearing,
                compassBearing = compassBearing,
                gpsBearing = gpsBearing,
                hasValidGpsBearing = hasValidGpsBearing,
                instructionText = instruction,
                remainingDistanceText = distance,
                routeSummary = NavStateManager.routeSummary,
                isObstacleInFront = NavStateManager.isObstacleInFront,
                travelVerdict = travelVerdict,
                cameraVerdict = cameraVerdict
        )
    }

    // ========================================
    //          UI HELPER METHODS
    // ========================================

    private fun updateInstructionText(text: String) {
        if (combinedMode) {
            lastInstructionCombined?.text = text
        } else {
            lastInstructionText.text = text
        }
    }

    private fun updateStatusText(text: String) {
        if (combinedMode) {
            statusCombined?.text = text
        } else {
            statusText.text = text
        }
    }

    private fun updateAnalysisButton() {
        btnToggleAnalysis.text = if (analysisEnabled) "Pause Analysis" else "Resume Analysis"
    }

    // ========================================
    //          IMAGE CONVERSION
    // ========================================

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        try {
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(image.planes[0].buffer)

            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap conversion failed", e)
            return null
        }
    }

    // ========================================
    //              CLEANUP
    // ========================================

    override fun onDestroy() {
        super.onDestroy()
        stopInAppNavigation()
        cameraExecutor.shutdown()
        reflexExecutor.shutdown()
        sensorManager.unregisterListener(sensorListener)
        uiScope.cancel()
        obstacleDetector.close()
        speechHelper.shutdown()
        NavStateManager.isObstacleInFront = false
    }
}
