
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

    /** When true, clients may POST bad STT samples to QA endpoints (multipart). Default off. */
    var transcriptionFeedbackEnabled: Boolean = false

    /** Refuse unsigned private file URLs (403). Off while we confirm every client uses the signed URLs the API returns. */
    var fileUrlEnforce: Boolean = false

    /** Refuse uploads outside [com.shifa.shared.UploadPolicy] (400). Off while we log what the apps really send. */
    var uploadPolicyEnforce: Boolean = false
}

@Component
@ConfigurationProperties(prefix = "daily")
class DailyProperties {
    lateinit var apiKey: String
    var apiUrl: String = "https://api.daily.co/v1"
}

@Component
@ConfigurationProperties(prefix = "stripe")
class StripeProperties {
    var secretKey: String = ""
    var webhookSecret: String = ""
    var connectRefreshUrl: String = ""
    var connectReturnUrl: String = ""
}
