package com.impairedvision.guideglass.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

object GeocoderHelper {

    suspend fun getAddressesFromLocationName(
            context: Context,
            query: String,
            maxResults: Int = 1
    ): List<Address>? =
            withContext(Dispatchers.IO) {
                if (!Geocoder.isPresent()) return@withContext null
                val geocoder = Geocoder(context, Locale.getDefault())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { continuation ->
                        geocoder.getFromLocationName(
                                query,
                                maxResults,
                                object : Geocoder.GeocodeListener {
                                    override fun onGeocode(addresses: MutableList<Address>) {
                                        if (continuation.isActive) {
                                            continuation.resume(addresses)
                                        }
                                    }

                                    override fun onError(errorMessage: String?) {
                                        if (continuation.isActive) {
                                            continuation.resume(null)
                                        }
                                    }
                                }
                        )
                    }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, maxResults)
                }
            }

    suspend fun getAddressesFromLocationNameBiased(
            context: Context,
            query: String,
            minLat: Double,
            minLng: Double,
            maxLat: Double,
            maxLng: Double,
            maxResults: Int = 3
    ): List<Address>? =
            withContext(Dispatchers.IO) {
                if (!Geocoder.isPresent()) return@withContext null
                val geocoder = Geocoder(context, Locale.getDefault())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { continuation ->
                        geocoder.getFromLocationName(
                                query,
                                maxResults,
                                minLat,
                                minLng,
                                maxLat,
                                maxLng,
                                object : Geocoder.GeocodeListener {
                                    override fun onGeocode(addresses: MutableList<Address>) {
                                        if (continuation.isActive) {
                                            continuation.resume(addresses)
                                        }
                                    }

                                    override fun onError(errorMessage: String?) {
                                        if (continuation.isActive) {
                                            continuation.resume(null)
                                        }
                                    }
                                }
                        )
                    }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(
                            query,
                            maxResults,
                            minLat,
                            minLng,
                            maxLat,
                            maxLng
                    )
                }
            }
}
