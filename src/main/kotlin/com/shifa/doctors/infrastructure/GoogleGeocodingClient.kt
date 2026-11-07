package com.shifa.doctors.infrastructure

import com.shifa.doctors.application.GeocodedAddress
import com.shifa.doctors.application.GeocodingGateway
import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration

/** Google Geocoding API (`googleMaps.apiKey`, the key the public config used to hand out). */
@Component
class GoogleGeocodingClient(
    @Value("\${googleMaps.apiKey:}") private val apiKey: String,
) : GeocodingGateway {
    private val log = LoggerFactory.getLogger(GoogleGeocodingClient::class.java)
    private val mapper = jacksonObjectMapper()
    private val client = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(10)).build()

    override fun isConfigured(): Boolean = apiKey.isNotBlank()

    override fun reverse(latitude: Double, longitude: Double, language: String): List<GeocodedAddress> =
        call("latlng", "$latitude,$longitude", language)

    override fun search(query: String, language: String): List<GeocodedAddress> = call("address", query, language)

    private fun call(param: String, value: String, language: String): List<GeocodedAddress> {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter(param, value)
            .addQueryParameter("language", language)
            .addQueryParameter("key", apiKey)
            .build()
        val body = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
        }.getOrElse {
            log.warn("Geocoding call failed: {}", it.javaClass.simpleName) // never log the URL: it carries the key
            throw upstream()
        }
        return parse(mapper.readTree(body)) ?: throw upstream()
    }

    private fun upstream() = CodedStatusException(HttpStatus.BAD_GATEWAY, "Geocoding provider failed", ErrorCode.UPSTREAM)

    companion object {
        private const val BASE_URL = "https://maps.googleapis.com/maps/api/geocode/json"
        private const val MAX_RESULTS = 5

        private fun items(node: JsonNode): List<JsonNode> = (0 until node.size()).map { node.get(it) }

        /** Google's answer as addresses; null when Google refused (anything but `OK` / `ZERO_RESULTS`). */
        fun parse(root: JsonNode): List<GeocodedAddress>? {
            when (root.path("status").asString("")) {
                "ZERO_RESULTS" -> return emptyList()
                "OK" -> Unit
                else -> return null
            }
            return items(root.path("results")).take(MAX_RESULTS).map { result ->
                val parts: List<Pair<List<String>, String>> = items(result.path("address_components")).map { c ->
                    items(c.path("types")).map { type -> type.asString("") } to c.path("long_name").asString("")
                }
                fun part(vararg types: String): String? = types.firstNotNullOfOrNull { type ->
                    parts.firstOrNull { (kinds, _) -> type in kinds }?.second?.takeIf { name -> name.isNotBlank() }
                }
                val street = listOfNotNull(part("route"), part("street_number")).joinToString(" ").ifBlank { null }
                val location = result.path("geometry").path("location")
                GeocodedAddress(
                    formattedAddress = result.path("formatted_address").asString("").ifBlank { null },
                    latitude = location.path("lat").takeIf { it.isNumber }?.asDouble(),
                    longitude = location.path("lng").takeIf { it.isNumber }?.asDouble(),
                    locationCountry = part("country"),
                    locationRegion = part("administrative_area_level_1"),
                    locationCity = part("locality", "postal_town"),
                    locationDistrict = part("sublocality_level_1", "sublocality", "administrative_area_level_2"),
                    locationPostalCode = part("postal_code"),
                    locationStreetAddress = street,
                )
            }
        }
    }
}
