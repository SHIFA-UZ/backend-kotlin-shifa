
// src/main/kotlin/com/shifa/config/AppProperties.kt
package com.shifa.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "app")
class AppProperties {
    // e.g., ./public-storage/images
    lateinit var storageRoot: String

    // e.g., http://localhost:8090
    lateinit var publicBaseUrl: String

    // e.g., https://shifa-doctor-staging.web.app
    var frontendUrl: String = ""
}

@Component
@ConfigurationProperties(prefix = "daily")
class DailyProperties {
    lateinit var apiKey: String
    var apiUrl: String = "https://api.daily.co/v1"
}
