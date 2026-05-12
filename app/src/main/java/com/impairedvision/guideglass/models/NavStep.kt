package com.impairedvision.guideglass.models

import com.google.android.gms.maps.model.LatLng

data class NavStep(
    val instruction: String,
    val distance: String,
    val duration: String,
    val start: LatLng,
    val end: LatLng,
    val polyline: String
)
