package com.shifa.shared.api

import com.shifa.shared.config.AppProperties
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
    @Value("\${googleMaps.apiKey:}")
    private val googleMapsApiKey: String,
    private val appProperties: AppProperties
) {
    
    /**
     * GET /api/public/config
     * Returns public configuration for frontend
     */
    @GetMapping
    fun getConfig(): Map<String, String> {
        return mapOf(
            "googleMapsApiKey" to googleMapsApiKey,
            "transcriptionFeedbackEnabled" to if (appProperties.transcriptionFeedbackEnabled) "true" else "false"
        )
    }
}
