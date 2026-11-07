package com.shifa.doctors.application

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import io.github.bucket4j.Bucket
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.Locale

/** One address in the doctor profile's fields (`locationCountry` … `locationStreetAddress`), plus the point and label. */
data class GeocodedAddress(
    val formattedAddress: String?,
    val latitude: Double?,
    val longitude: Double?,
    val locationCountry: String?,
    val locationRegion: String?,
    val locationCity: String?,
    val locationDistrict: String?,
    val locationPostalCode: String?,
    val locationStreetAddress: String?,
)

/** The geocoder behind the `/api/geocode` routes (OpenStreetMap, `doctors.infrastructure.NominatimGeocodingClient`). */
interface GeocodingGateway {
    /** False when the provider is switched off: the routes then answer 503. */
    fun isConfigured(): Boolean

    /** Best matches first; empty when nothing is found. Throws on a provider failure. */
    fun reverse(latitude: Double, longitude: Double, language: String): List<GeocodedAddress>

    fun search(query: String, language: String): List<GeocodedAddress>
}

/**
 * Geocoding for the apps (BE-15): answers are cached a day per query and language (addresses rarely move, and
 * OpenStreetMap asks for caching), and each account may ask [PER_MINUTE] times a minute (429 `RATE_LIMITED` beyond).
 */
@Service
class GeocodingService(private val gateway: GeocodingGateway) {
    private val answers: Cache<String, List<GeocodedAddress>> =
        Caffeine.newBuilder().expireAfterWrite(Duration.ofDays(1)).maximumSize(5_000).build()
    private val buckets: Cache<String, Bucket> =
        Caffeine.newBuilder().expireAfterAccess(Duration.ofHours(1)).maximumSize(100_000).build()

    fun reverse(account: String, latitude: Double, longitude: Double, lang: String?): List<GeocodedAddress> {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "lat must be within ±90 and lng within ±180" }
        val language = language(lang)
        // ~1 m: nearby pins share one answer.
        val key = "r:%.5f,%.5f:%s".format(Locale.ROOT, latitude, longitude, language)
        return cached(account, key) { gateway.reverse(latitude, longitude, language) }
    }

    fun search(account: String, query: String, lang: String?): List<GeocodedAddress> {
        val q = query.trim()
        require(q.length in 2..200) { "q must be 2 to 200 characters" }
        val language = language(lang)
        return cached(account, "s:${q.lowercase()}:$language") { gateway.search(q, language) }
    }

    private fun cached(account: String, key: String, load: () -> List<GeocodedAddress>): List<GeocodedAddress> {
        answers.getIfPresent(key)?.let { return it }
        if (!gateway.isConfigured()) {
            throw CodedStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Geocoding is not configured", ErrorCode.UNAVAILABLE)
        }
        if (!buckets.get(account) { newBucket() }.tryConsume(1)) {
            throw CodedStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many geocoding requests", ErrorCode.RATE_LIMITED)
        }
        return load().also { answers.put(key, it) }
    }

    private fun newBucket(): Bucket =
        Bucket.builder().addLimit { it.capacity(PER_MINUTE).refillGreedy(PER_MINUTE, Duration.ofMinutes(1)) }.build()

    private fun language(lang: String?): String = lang?.lowercase()?.takeIf { it in LANGUAGES } ?: "en"

    companion object {
        const val PER_MINUTE = 30L
        private val LANGUAGES = setOf("en", "uz", "ru")
    }
}
