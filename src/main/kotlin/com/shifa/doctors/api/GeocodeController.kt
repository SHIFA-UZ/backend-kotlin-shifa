package com.shifa.doctors.api

import com.shifa.doctors.api.dto.GeocodeResultsDto
import com.shifa.doctors.application.GeocodingService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Address lookups for the apps (BE-15), so no client carries the Google key. Any signed-in account; `lang` is
 * `en` (default), `uz` or `ru`. 400 on bad input, 429 `RATE_LIMITED` (30 a minute per account), 502 `UPSTREAM`,
 * 503 `UNAVAILABLE` when no key is configured.
 */
@RestController
@RequestMapping("/api/geocode")
class GeocodeController(private val geocoding: GeocodingService) {

    @GetMapping("/reverse")
    fun reverse(
        @AuthenticationPrincipal principal: UserDetails,
        @RequestParam lat: Double,
        @RequestParam lng: Double,
        @RequestParam(required = false) lang: String?,
    ): GeocodeResultsDto = GeocodeResultsDto(geocoding.reverse(principal.username, lat, lng, lang))

    @GetMapping("/search")
    fun search(
        @AuthenticationPrincipal principal: UserDetails,
        @RequestParam q: String,
        @RequestParam(required = false) lang: String?,
    ): GeocodeResultsDto = GeocodeResultsDto(geocoding.search(principal.username, q, lang))
}
