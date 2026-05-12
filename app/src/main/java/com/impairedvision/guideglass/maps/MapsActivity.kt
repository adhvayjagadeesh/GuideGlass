package com.impairedvision.guideglass.maps

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMap.OnPoiClickListener
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.snackbar.Snackbar
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.impairedvision.guideglass.R
import com.impairedvision.guideglass.tts.SpeechHelper
import kotlinx.coroutines.*
import android.util.Log

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val TAG = "MapsActivity"
    }

    private var googleMap: GoogleMap? = null
    private var originMarker: Marker? = null
    private var destMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var navMarker: Marker? = null

    private val uiScope = CoroutineScope(Job() + Dispatchers.Main)

    // Speech Helper
    private lateinit var speechHelper: SpeechHelper

    // Navigation UI
    private lateinit var navPanel: View
    private lateinit var navSummary: TextView
    private lateinit var stepsRecycler: RecyclerView
    private var stepsAdapter: StepsAdapter? = null

    // In-app navigation state
    private var navigating: Boolean = false
    private var navigationLocationCallback: LocationCallback? = null
    private var currentStepIndex: Int = 0
    private var navSteps: List<NavStep> = emptyList()

    // Search UI
    private lateinit var searchDestinationView: TextView

    // Last known location
    private var lastKnownLocation: LatLng? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                enableMyLocation()
                centerOnLastKnownLocationIfAvailable()
            }
        }

    // Autocomplete result launcher
    private val autocompleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val place = Autocomplete.getPlaceFromIntent(result.data!!)
                val destLatLng = place.latLng
                if (destLatLng != null) {
                    searchDestinationView.text = place.name ?: place.address ?: "Destination"

                    destMarker?.remove()
                    destMarker = googleMap?.addMarker(
                        MarkerOptions()
                            .position(destLatLng)
                            .title(place.name ?: "Destination")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    )

                    if (originMarker == null && lastKnownLocation != null) {
                        originMarker = googleMap?.addMarker(
                            MarkerOptions()
                                .position(lastKnownLocation!!)
                                .title("Origin")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                        )
                    }

                    googleMap?.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(destLatLng, 14f)
                    )

                    // Announce destination
                    speechHelper.speak("Destination set to ${place.name ?: "selected location"}")

                    fetchAndDrawRoute()
                }
            } else if (result.resultCode != Activity.RESULT_CANCELED) {
                result.data?.let {
                    val status = Autocomplete.getStatusFromIntent(it)
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Search error: ${status.statusMessage}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        // Initialize Speech Helper
        speechHelper = SpeechHelper(this)

        // Initialize Places SDK
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, getString(R.string.google_maps_key))
        }

        // Bottom navigation panel
        navPanel = findViewById(R.id.nav_panel)
        navSummary = findViewById(R.id.tv_nav_summary)
        stepsRecycler = findViewById(R.id.rv_steps)
        stepsRecycler.layoutManager = LinearLayoutManager(this)

        // Start/stop navigation button
        val startNavButton: Button = findViewById(R.id.btn_start_navigation)
        startNavButton.setOnClickListener {
            if (navigating) {
                stopInAppNavigation()
                startNavButton.text = "Start Navigation"
                speechHelper.speak("Navigation stopped")
            } else {
                startInAppNavigation()
                startNavButton.text = "Stop Navigation"
            }
        }

        // Search bar
        searchDestinationView = findViewById(R.id.tv_search_destination)
        searchDestinationView.setOnClickListener { openDestinationSearch() }

        // Map fragment
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Welcome message
        speechHelper.speak("Maps navigation ready. Tap the search bar to enter a destination.")
    }

    private fun openDestinationSearch() {
        val fields = listOf(
            Place.Field.ID,
            Place.Field.NAME,
            Place.Field.LAT_LNG,
            Place.Field.ADDRESS
        )

        val intent = Autocomplete.IntentBuilder(AutocompleteActivityMode.OVERLAY, fields)
            .build(this)

        autocompleteLauncher.launch(intent)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = true
        map.uiSettings.isMapToolbarEnabled = true

        // Default location (San Francisco)
        val start = LatLng(37.7749, -122.4194)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 12f))

        // Single tap to drop pin
        map.setOnMapClickListener { latLng ->
            map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Dropped Pin")
                    .snippet("${latLng.latitude}, ${latLng.longitude}")
            )
        }

        // Long press to set origin/destination
        map.setOnMapLongClickListener { latLng ->
            when {
                originMarker == null -> {
                    clearRoute()
                    originMarker = map.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title("Origin")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    )
                    Toast.makeText(this, "Origin set.", Toast.LENGTH_SHORT).show()
                    speechHelper.speak("Origin set")
                }
                destMarker == null -> {
                    destMarker = map.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title("Destination")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    )
                    speechHelper.speak("Destination set. Getting directions.")
                    fetchAndDrawRoute()
                }
                else -> {
                    clearRoute()
                    originMarker = map.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title("Origin")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    )
                    Toast.makeText(this, "Origin reset.", Toast.LENGTH_SHORT).show()
                    speechHelper.speak("Origin reset")
                }
            }
        }

        // POI click listener
        map.setOnPoiClickListener(OnPoiClickListener { poi ->
            map.addMarker(
                MarkerOptions().position(poi.latLng).title(poi.name)
            )?.showInfoWindow()
            speechHelper.speak("Point of interest: ${poi.name}")
        })

        enableMyLocation()
        centerOnLastKnownLocationIfAvailable()
    }

    private fun clearRoute() {
        stopInAppNavigation()
        findViewById<Button>(R.id.btn_start_navigation)?.text = "Start Navigation"

        routePolyline?.remove()
        routePolyline = null

        navMarker?.remove()
        navMarker = null

        originMarker?.remove()
        originMarker = null

        destMarker?.remove()
        destMarker = null

        navPanel.visibility = View.GONE
        navSteps = emptyList()
        currentStepIndex = 0
    }

    private fun fetchAndDrawRoute() {
        val map = googleMap ?: return
        val origin = originMarker?.position ?: lastKnownLocation ?: return
        val dest = destMarker?.position ?: return

        val originStr = "${origin.latitude},${origin.longitude}"
        val destStr = "${dest.latitude},${dest.longitude}"

        //val apiKey = getString(R.string.google_maps_key)
        val apiKey = getString(R.string.google_directions_key)

        uiScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
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
                    ).show()
                    speechHelper.speak("Could not get directions. ${resp.error_message ?: resp.status}")
                    return@launch
                }

                val route = resp.routes.first()
                val points = PolylineDecoder.decode(route.overview_polyline.points)

                routePolyline?.remove()
                routePolyline = map.addPolyline(
                    PolylineOptions()
                        .addAll(points)
                        .width(12f)
                        .color(0xFF1E88E5.toInt())
                        .geodesic(true)
                )

                val bounds = LatLngBounds.builder()
                points.forEach { bounds.include(it) }
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100))

                val leg = route.legs.firstOrNull()
                if (leg != null) {
                    showNavigationPanel(leg)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching directions", e)
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "Error: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
                speechHelper.speak("Error getting directions")
            }
        }
    }

    // Navigation step data class
    data class NavStep(
        val instruction: String,
        val distance: String,
        val start: LatLng,
        val end: LatLng
    )

    private fun showNavigationPanel(leg: Leg) {
        navSummary.text = "Distance: ${leg.distance.text} • Duration: ${leg.duration.text}"

        // Announce route summary
        speechHelper.speak("Route found. ${leg.distance.text}, about ${leg.duration.text}")

        // Build step list
        navSteps = leg.steps.map { step ->
            val instr = HtmlCompat.fromHtml(
                step.htmlInstructions ?: "",
                HtmlCompat.FROM_HTML_MODE_LEGACY
            ).toString()

            NavStep(
                instruction = instr,
                distance = step.distance.text,
                start = LatLng(step.startLocation.lat, step.startLocation.lng),
                end = LatLng(step.endLocation.lat, step.endLocation.lng)
            )
        }

        // Speak first step
        if (navSteps.isNotEmpty()) {
            speechHelper.speakNavigation("First, ${navSteps.first().instruction}")
        }

        // Setup RecyclerView
        stepsAdapter = StepsAdapter(navSteps) { step, index ->
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(step.start, 16f))

            navMarker?.remove()
            navMarker = googleMap?.addMarker(
                MarkerOptions().position(step.start).title(step.instruction)
            )
            navMarker?.showInfoWindow()

            // Speak the step when tapped
            speechHelper.speakNavigation("${step.instruction}. ${step.distance}")
        }

        stepsRecycler.adapter = stepsAdapter
        navPanel.visibility = View.VISIBLE
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
            val itemView = layoutInflater.inflate(
                android.R.layout.simple_list_item_2,
                parent,
                false
            )
            return StepViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
            val step = steps[position]
            holder.title.text = step.instruction
            holder.subtitle.text = step.distance
            holder.itemView.setOnClickListener {
                onStepClick(step, position)
            }
        }

        override fun getItemCount() = steps.size
    }

    // ---------- IN-APP NAVIGATION ----------

    private fun startInAppNavigation() {
        val map = googleMap ?: return

        if (routePolyline == null || navSteps.isEmpty()) {
            Toast.makeText(this, "Get directions first.", Toast.LENGTH_SHORT).show()
            speechHelper.speak("Please set a destination first")
            return
        }

        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        val hasFine = ContextCompat.checkSelfPermission(this, fine) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, coarse) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Toast.makeText(this, "Location permission required.", Toast.LENGTH_SHORT).show()
            speechHelper.speak("Location permission required")
            return
        }

        val fused = LocationServices.getFusedLocationProviderClient(this)

        if (navigationLocationCallback == null) {
            navigationLocationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    if (!navigating) return
                    val loc = result.lastLocation ?: return
                    val here = LatLng(loc.latitude, loc.longitude)
                    lastKnownLocation = here

                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(here, 17f)
                    )

                    // Check if we've reached the next step
                    checkNavigationProgress(here)
                }
            }
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2000L
        )
            .setMinUpdateDistanceMeters(3f)
            .build()

        navigating = true
        currentStepIndex = 0
        fused.requestLocationUpdates(
            request,
            navigationLocationCallback!!,
            Looper.getMainLooper()
        )

        Toast.makeText(this, "Navigation started.", Toast.LENGTH_SHORT).show()
        speechHelper.speak("Navigation started. ${navSteps.firstOrNull()?.instruction ?: ""}")
    }

    private fun checkNavigationProgress(currentLocation: LatLng) {
        if (currentStepIndex >= navSteps.size) return

        val currentStep = navSteps[currentStepIndex]
        val distanceToStepEnd = distanceBetween(currentLocation, currentStep.end)

        // If within 20 meters of step end, move to next step
        if (distanceToStepEnd < 20) {
            currentStepIndex++
            if (currentStepIndex < navSteps.size) {
                val nextStep = navSteps[currentStepIndex]
                speechHelper.speakNavigation("Now, ${nextStep.instruction}")
            } else {
                speechHelper.speak("You have arrived at your destination")
                stopInAppNavigation()
            }
        }
    }

    private fun distanceBetween(point1: LatLng, point2: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            point1.latitude, point1.longitude,
            point2.latitude, point2.longitude,
            results
        )
        return results[0]
    }

    private fun stopInAppNavigation() {
        if (!navigating) return
        navigating = false

        navigationLocationCallback?.let { cb ->
            val fused = LocationServices.getFusedLocationProviderClient(this)
            fused.removeLocationUpdates(cb)
        }

        Toast.makeText(this, "Navigation stopped.", Toast.LENGTH_SHORT).show()
    }

    // ---------- LOCATION HANDLING ----------

    private fun enableMyLocation() {
        val map = googleMap ?: return
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION

        val hasFine = ContextCompat.checkSelfPermission(this, fine) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, coarse) == PackageManager.PERMISSION_GRANTED

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

    private fun centerOnLastKnownLocationIfAvailable() {
        val map = googleMap ?: return
        val fused = LocationServices.getFusedLocationProviderClient(this)

        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION

        val hasLocation =
            ContextCompat.checkSelfPermission(this, fine) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, coarse) == PackageManager.PERMISSION_GRANTED

        if (!hasLocation) return

        try {
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

    // ---------- MENU ----------

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_maps, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        val map = googleMap ?: return super.onOptionsItemSelected(item)
        when (item.itemId) {
            R.id.action_normal -> map.mapType = GoogleMap.MAP_TYPE_NORMAL
            R.id.action_satellite -> map.mapType = GoogleMap.MAP_TYPE_SATELLITE
            R.id.action_terrain -> map.mapType = GoogleMap.MAP_TYPE_TERRAIN
            R.id.action_hybrid -> map.mapType = GoogleMap.MAP_TYPE_HYBRID
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- CLEANUP ----------

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
        speechHelper.shutdown()
    }
}
