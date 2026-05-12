package com.impairedvision.guideglass.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FindCurrentPlaceRequest
import kotlinx.coroutines.tasks.await

data class PlaceResult(val name: String, val latLng: LatLng)

class PlacesRepository(private val context: Context) {

    private val placesClient = Places.createClient(context)
    private val geocoder = Geocoder(context)

    companion object {
        private const val BIAS_DEGREES = 0.05 // Roughly 5km
    }

    suspend fun findNearestPlaceByType(placeTypeQuery: String, canonicalQuery: String): PlaceResult? {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val placeFields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.TYPES)
        val request = FindCurrentPlaceRequest.newInstance(placeFields)

        return try {
            val response = placesClient.findCurrentPlace(request).await()
            
            // Priority 1: match by PlaceType string
            var bestMatch = response.placeLikelihoods
                .filter { pl ->
                    pl.place.placeTypes?.any { t ->
                        t.toString().lowercase().contains(canonicalQuery.lowercase())
                    } == true
                }
                .maxByOrNull { it.likelihood }

            // Priority 2: match by place name (exact, then alias)
            if (bestMatch == null) {
                bestMatch = response.placeLikelihoods
                    .filter { pl ->
                        val name = pl.place.name?.lowercase() ?: ""
                        name.contains(canonicalQuery.lowercase()) ||
                        name.contains(placeTypeQuery.lowercase())
                    }
                    .maxByOrNull { it.likelihood }
            }

            bestMatch?.place?.let { place ->
                place.latLng?.let { latLng ->
                    PlaceResult(place.name ?: canonicalQuery, latLng)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    fun geocodeDestination(query: String, canonicalQuery: String, location: Location?): LatLng? {
        return try {
            var addresses = if (location != null) {
                geocoder.getFromLocationName(
                    query, 3,
                    location.latitude - BIAS_DEGREES,
                    location.longitude - BIAS_DEGREES,
                    location.latitude + BIAS_DEGREES,
                    location.longitude + BIAS_DEGREES
                )
            } else {
                geocoder.getFromLocationName(query, 3)
            }

            if (addresses.isNullOrEmpty() && canonicalQuery != query) {
                addresses = if (location != null) {
                    geocoder.getFromLocationName(
                        canonicalQuery, 3,
                        location.latitude - BIAS_DEGREES,
                        location.longitude - BIAS_DEGREES,
                        location.latitude + BIAS_DEGREES,
                        location.longitude + BIAS_DEGREES
                    )
                } else {
                    geocoder.getFromLocationName(canonicalQuery, 3)
                }
            }

            addresses?.firstOrNull()?.let {
                LatLng(it.latitude, it.longitude)
            }
        } catch (e: Exception) {
            null
        }
    }
}
