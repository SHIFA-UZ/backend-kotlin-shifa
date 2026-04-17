package com.shifa.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public configuration endpoint
 * Returns frontend configuration values that don't require authentication
 */
@RestController
@RequestMapping("/api/public/config")
class ConfigController(
    @Value("\${GOOGLE_MAPS_API_KEY:}")
    private val googleMapsApiKey: String
) {
    
    /**
     * GET /api/public/config
     * Returns public configuration for frontend
     */
    @GetMapping
    fun getConfig(): Map<String, String> {
        return mapOf(
            "googleMapsApiKey" to googleMapsApiKey
        )
    }
}
