package com.impairedvision.guideglass.data

import com.google.android.gms.maps.model.LatLng
import com.impairedvision.guideglass.maps.PolylineDecoder
import com.impairedvision.guideglass.maps.RetrofitClients
import com.impairedvision.guideglass.models.NavStep

data class RouteResult(
    val polyline: List<LatLng>,
    val steps: List<NavStep>,
    val distanceText: String,
    val durationText: String
)

class RouteRepository {
    suspend fun getWalkingDirections(origin: LatLng, destination: LatLng, apiKey: String): RouteResult? {
        return try {
            val originStr = "${origin.latitude},${origin.longitude}"
            val destStr = "${destination.latitude},${destination.longitude}"

            val resp = RetrofitClients.directions.directions(
                origin = originStr,
                destination = destStr,
                mode = "walking",
                key = apiKey
            )

            if (resp.status != "OK" || resp.routes.isEmpty()) {
                return null
            }

            val route = resp.routes.first()
            val points = PolylineDecoder.decode(route.overview_polyline.points)

            val leg = route.legs.firstOrNull() ?: return null

            val navSteps = leg.steps.map { step ->
                val instr = androidx.core.text.HtmlCompat.fromHtml(
                    step.htmlInstructions ?: "",
                    androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
                ).toString()

                NavStep(
                    instruction = instr,
                    distance = step.distance.text,
                    duration = step.duration?.text ?: "",
                    start = LatLng(step.startLocation.lat, step.startLocation.lng),
                    end = LatLng(step.endLocation.lat, step.endLocation.lng),
                    polyline = step.polyline?.points ?: ""
                )
            }

            RouteResult(
                polyline = points,
                steps = navSteps,
                distanceText = leg.distance.text,
                durationText = leg.duration.text
            )
        } catch (e: Exception) {
            null
        }
    }
}
