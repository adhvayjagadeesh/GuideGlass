package com.impairedvision.guideglass.maps

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Query

interface DirectionsService {
    @GET("maps/api/directions/json")
    suspend fun directions(
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("mode") mode: String = "walking",
        @Query("key") key: String
    ): DirectionsResponse
}

// Top-level response
data class DirectionsResponse(
    val routes: List<Route> = emptyList(),
    val status: String,
    val error_message: String? = null
)

// A single route
data class Route(
    val overview_polyline: OverviewPolyline,
    val legs: List<Leg> = emptyList()
)

// Encoded polyline string
data class OverviewPolyline(
    val points: String
)

// One leg of the trip (usually origin → destination)
data class Leg(
    val distance: ValueText,
    val duration: ValueText,
    @Json(name = "start_address") val startAddress: String,
    @Json(name = "end_address") val endAddress: String,
    val steps: List<Step> = emptyList()
)

// One step in the route (turn-by-turn)
data class Step(
    val distance: ValueText,
    val duration: ValueText,
    @Json(name = "html_instructions") val htmlInstructions: String? = null,
    @Json(name = "start_location") val startLocation: StepLocation,
    @Json(name = "end_location") val endLocation: StepLocation,
    val polyline: OverviewPolyline? = null
)

// LatLng for a step
data class StepLocation(
    val lat: Double,
    val lng: Double
)

// Common distance/duration structure
data class ValueText(
    val value: Long,
    val text: String
)
