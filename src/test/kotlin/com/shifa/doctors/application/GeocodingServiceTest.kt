package com.shifa.doctors.application

import com.shifa.doctors.infrastructure.NominatimGeocodingClient
import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper

/** BE-15: OpenStreetMap's answer becomes the profile's address fields; the proxy caches, rate-limits and validates. */
class GeocodingServiceTest {
    private class FakeGateway(var configured: Boolean = true) : GeocodingGateway {
        var calls = 0
        override fun isConfigured() = configured
        override fun reverse(latitude: Double, longitude: Double, language: String) = answer()
        override fun search(query: String, language: String) = answer()
        private fun answer(): List<GeocodedAddress> {
            calls++
            return listOf(GeocodedAddress("x", 41.3, 69.2, "Uzbekistan", null, "Tashkent", null, null, null))
        }
    }

    @Test
    fun `openstreetmap places map to the profile fields`() {
        val search = """
            [{"lat":"41.3110","lon":"69.2790","display_name":"1, Amir Temur, Yunusobod, Tashkent, 100000, Uzbekistan",
              "address":{"house_number":"1","road":"Amir Temur","suburb":"Yunusobod","city":"Tashkent",
                "state":"Tashkent Region","postcode":"100000","country":"Uzbekistan","country_code":"uz"}}]
        """.trimIndent()
        val address = NominatimGeocodingClient.parse(jacksonObjectMapper().readTree(search))!!.single()
        assertEquals("Uzbekistan", address.locationCountry)
        assertEquals("Tashkent Region", address.locationRegion)
        assertEquals("Tashkent", address.locationCity)
        assertEquals("Yunusobod", address.locationDistrict)
        assertEquals("100000", address.locationPostalCode)
        assertEquals("Amir Temur 1", address.locationStreetAddress)
        assertEquals(41.311, address.latitude)
        assertEquals(69.279, address.longitude)
        assertEquals("1, Amir Temur, Yunusobod, Tashkent, 100000, Uzbekistan", address.formattedAddress)

        // Reverse answers one place; a town stands in for a city.
        val reverse = """{"lat":"40.1","lon":"65.3","display_name":"Navoiy","address":{"town":"Navoiy","country":"Uzbekistan"}}"""
        assertEquals("Navoiy", NominatimGeocodingClient.parse(jacksonObjectMapper().readTree(reverse))!!.single().locationCity)
        assertTrue(NominatimGeocodingClient.parse(jacksonObjectMapper().readTree("[]"))!!.isEmpty())
        assertTrue(NominatimGeocodingClient.parse(jacksonObjectMapper().readTree("""{"error":"Unable to geocode"}"""))!!.isEmpty())
        assertNull(NominatimGeocodingClient.parse(jacksonObjectMapper().readTree("{}"))) // a strange answer: 502, not cached
    }

    @Test
    fun `answers are cached per query and language, input is validated`() {
        val gateway = FakeGateway()
        val service = GeocodingService(gateway)
        service.reverse("7", 41.311111, 69.279999, "ru")
        service.reverse("7", 41.311112, 69.279999, "ru") // same point to ~1 m
        assertEquals(1, gateway.calls)
        service.reverse("7", 41.311111, 69.279999, "uz")
        assertEquals(2, gateway.calls)
        service.search("7", "Amir Temur", null)
        service.search("7", "  amir temur ", "en")
        assertEquals(3, gateway.calls)

        assertThrows<IllegalArgumentException> { service.reverse("7", 91.0, 0.0, null) }
        assertThrows<IllegalArgumentException> { service.search("7", "a", null) }
    }

    @Test
    fun `no key is 503, too many calls are 429`() {
        val off = assertThrows<CodedStatusException> { GeocodingService(FakeGateway(configured = false)).search("7", "Tashkent", null) }
        assertEquals(503, off.statusCode.value())
        assertEquals(ErrorCode.UNAVAILABLE, off.code)

        val service = GeocodingService(FakeGateway())
        repeat(GeocodingService.PER_MINUTE.toInt()) { service.search("8", "street $it", null) }
        val limited = assertThrows<CodedStatusException> { service.search("8", "one more", null) }
        assertEquals(429, limited.statusCode.value())
        assertEquals(ErrorCode.RATE_LIMITED, limited.code)
        service.search("9", "another account", null) // per account
    }
}
