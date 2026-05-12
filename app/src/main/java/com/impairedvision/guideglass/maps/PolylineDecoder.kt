package com.impairedvision.guideglass.maps

import com.google.android.gms.maps.model.LatLng

object PolylineDecoder {
    /**
     * Decodes an encoded polyline (Google Encoded Polyline Algorithm Format)
     * @param encoded The encoded polyline string
     * @return List of LatLng points
     */
    fun decode(encoded: String): List<LatLng> {
        val path = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            path.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return path
    }
}
