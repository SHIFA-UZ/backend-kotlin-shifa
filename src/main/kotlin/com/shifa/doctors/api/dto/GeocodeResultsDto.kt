package com.shifa.doctors.api.dto

import com.shifa.doctors.application.GeocodedAddress

/** `{results}` of a geocoding call, best match first (at most 5; empty when nothing matched). */
data class GeocodeResultsDto(val results: List<GeocodedAddress>)
