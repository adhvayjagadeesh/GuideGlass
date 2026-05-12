package com.impairedvision.guideglass.voice

import android.location.Location
import com.impairedvision.guideglass.data.PlacesRepository
import com.impairedvision.guideglass.data.PlaceResult
import com.google.android.gms.maps.model.LatLng

sealed class VoiceIntent {
    object Cancel : VoiceIntent()
    data class Nearest(val query: String, val canonicalQuery: String) : VoiceIntent()
    data class Navigate(val query: String, val canonicalQuery: String) : VoiceIntent()
    data class GeneralGoal(val goal: String) : VoiceIntent()
}

class VoiceCommandManager(private val placesRepository: PlacesRepository) {

    private val COMMON_POI_ALIASES = mapOf(
        "Starbucks" to listOf("coffee", "starbuck", "star bucks"),
        "CVS" to listOf("pharmacy", "drugstore", "drug store", "cvs pharmacy"),
        "McDonald's" to listOf("mcdonald", "mcdonalds", "macdonalds", "fast food")
    )

    fun parseCommand(command: String): VoiceIntent {
        val raw = command.trim()
        val lc = raw.lowercase()

        // 1. Cancel / stop
        if (Regex("""\b(stop|cancel|never\s*mind|quit|exit)\b""").containsMatchIn(lc)) {
            return VoiceIntent.Cancel
        }

        // 2. Nearest-place intent
        val nearestRegex = Regex(
            """\b(nearest|closest|find\s+(?:me\s+)?(?:a|an|the)?|where\s+is\s+(?:the)?)\s+(.+)""",
            RegexOption.IGNORE_CASE
        )
        nearestRegex.find(lc)?.let { match ->
            val query = match.groupValues[2].trim()
            if (query.isNotBlank()) {
                return VoiceIntent.Nearest(query, resolvePoiAlias(query))
            }
        }

        // 3. Navigate-to intent
        val navRegex = Regex(
            """\b(navigate|directions?|take\s+me|go|head)\s+to\s+(.+)""",
            RegexOption.IGNORE_CASE
        )
        navRegex.find(lc)?.let { match ->
            val query = match.groupValues[2].trim()
            if (query.isNotBlank()) {
                return VoiceIntent.Navigate(query, resolvePoiAlias(query))
            }
        }

        // 4. Free-form goal
        return VoiceIntent.GeneralGoal(raw)
    }

    suspend fun findNearestPlace(intent: VoiceIntent.Nearest): PlaceResult? {
        return placesRepository.findNearestPlaceByType(intent.query, intent.canonicalQuery)
    }

    suspend fun geocodeDestination(intent: VoiceIntent.Navigate, location: Location?): LatLng? {
        return placesRepository.geocodeDestination(intent.query, intent.canonicalQuery, location)
    }

    private fun resolvePoiAlias(query: String): String {
        val lq = query.lowercase().trim()
        for ((canonical, aliases) in COMMON_POI_ALIASES) {
            if (canonical.lowercase() == lq) return canonical
            if (aliases.any { lq.contains(it) || it.contains(lq) }) return canonical
        }
        return query
    }
}
