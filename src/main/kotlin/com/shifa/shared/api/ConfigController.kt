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
    private val appProperties: AppProperties,
    private val contributors: List<PublicConfigContributor>,
) {

    /**
     * GET /api/public/config
     * The two original string keys exactly as before, then each feature's blocks (e.g. `doctorApp`).
     */
    @GetMapping
    fun getConfig(): Map<String, Any?> {
        val config = linkedMapOf<String, Any?>(
            "googleMapsApiKey" to googleMapsApiKey,
            "transcriptionFeedbackEnabled" to if (appProperties.transcriptionFeedbackEnabled) "true" else "false",
        )
        contributors.forEach { config.putAll(it.publicConfig()) }
        return config
    }
}
