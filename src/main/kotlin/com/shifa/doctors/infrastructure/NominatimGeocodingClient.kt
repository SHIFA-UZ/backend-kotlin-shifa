package com.shifa.doctors.infrastructure

import com.shifa.doctors.application.GeocodedAddress
import com.shifa.doctors.application.GeocodingGateway
import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * OpenStreetMap's Nominatim, the same map data the apps draw (the user's call 2026-11-07: no Google, no key, no
 * billing). Its usage policy asks for at most one request a second from the whole server, a User-Agent naming the
 * app, and cached answers ([com.shifa.doctors.application.GeocodingService] keeps them a day).
 */
@Component
class NominatimGeocodingClient : GeocodingGateway {
    private val log = LoggerFactory.getLogger(NominatimGeocodingClient::class.java)
    private val mapper = jacksonObjectMapper()
    private val client = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(10)).build()
    private val gate = ReentrantLock(true)
    private var lastSent: Long? = null // System.nanoTime() when the previous call ended; read and written under [gate]

    override fun isConfigured(): Boolean = true

    override fun reverse(latitude: Double, longitude: Double, language: String): List<GeocodedAddress> =
        call("reverse", language) { it.addQueryParameter("lat", "$latitude").addQueryParameter("lon", "$longitude") }

    override fun search(query: String, language: String): List<GeocodedAddress> =
        call("search", language) { it.addQueryParameter("q", query).addQueryParameter("limit", "$MAX_RESULTS") }

    private fun call(path: String, language: String, params: (HttpUrl.Builder) -> HttpUrl.Builder): List<GeocodedAddress> {
        val url = params(
            "$BASE_URL/$path".toHttpUrl().newBuilder()
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("addressdetails", "1")
                .addQueryParameter("accept-language", language),
        ).build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        // Never log the URL or the body: the query is an address someone typed.
        val places = oneAtATime {
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        parse(mapper.readTree(response.body.string()))
                    } else {
                        log.warn("Geocoding call failed: HTTP {}", response.code)
                        null
                    }
                }
            }.getOrElse {
                log.warn("Geocoding call failed: {}", it.javaClass.simpleName)
                null
            }
        }
        return places ?: throw CodedStatusException(HttpStatus.BAD_GATEWAY, "Geocoding provider failed", ErrorCode.UPSTREAM)
    }

    /**
     * One call at a time for the whole server, starting at least [INTERVAL] after the previous one ended; a caller who
     * can't get its turn within [MAX_WAIT] gets 429 at once instead of piling up.
     */
    private fun <T> oneAtATime(call: () -> T): T {
        // ponytail: one instance on Railway, so this lock is the server's rate; share it (Redis) if it scales out.
        if (!gate.tryLock(MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw CodedStatusException(HttpStatus.TOO_MANY_REQUESTS, "Address lookup is busy, try again", ErrorCode.RATE_LIMITED)
        }
        try {
            lastSent?.let { ended ->
                val wait = INTERVAL.toNanos() - (System.nanoTime() - ended) // elapsed time: safe across nanoTime overflow
                if (wait > 0) Thread.sleep(Duration.ofNanos(wait))
            }
            return try {
                call()
            } finally {
                lastSent = System.nanoTime()
            }
        } finally {
            gate.unlock()
        }
    }

    companion object {
        private const val BASE_URL = "https://nominatim.openstreetmap.org"
        private const val USER_AGENT = "ShifaBackend/1.0 (+https://shifa.uz)"
        private const val MAX_RESULTS = 5
        private val INTERVAL: Duration = Duration.ofSeconds(1)
        private val MAX_WAIT: Duration = Duration.ofSeconds(3)

        /**
         * Search answers a list, reverse one place (or `{"error": …}` when nothing is there); null for any other shape,
         * so a strange answer is a 502 and never cached. Places without a point are dropped.
         */
        fun parse(root: JsonNode): List<GeocodedAddress>? {
            val places = when {
                root.isArray -> (0 until root.size()).map { root.get(it) }
                root.has("error") -> emptyList()
                root.has("lat") -> listOf(root)
                else -> return null
            }
            return places.filter { it.has("lat") && it.has("lon") }.take(MAX_RESULTS).map { place ->
                val address = place.path("address")
                fun part(vararg keys: String): String? =
                    keys.firstNotNullOfOrNull { key -> address.path(key).asString("").takeIf { it.isNotBlank() } }
                GeocodedAddress(
                    formattedAddress = place.path("display_name").asString("").ifBlank { null },
                    latitude = place.path("lat").asString("").toDoubleOrNull(),
                    longitude = place.path("lon").asString("").toDoubleOrNull(),
                    locationCountry = part("country"),
                    locationRegion = part("state", "region", "province"),
                    locationCity = part("city", "town", "village", "municipality"),
                    locationDistrict = part("city_district", "district", "suburb", "county"),
                    locationPostalCode = part("postcode"),
                    locationStreetAddress = listOfNotNull(part("road", "pedestrian", "street"), part("house_number"))
                        .joinToString(" ").ifBlank { null },
                )
            }
        }
    }
}
