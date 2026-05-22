package com.shifa.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.rate-limit.ai")
class AiRateLimitProperties {
    var enabled: Boolean = true
    var patientDailyRequests: Int = 30
    var doctorDailyRequests: Int = 100
    var patientBurstPerMin: Int = 10
    var doctorBurstPerMin: Int = 20
    /** IANA zone id for daily reset (midnight in this zone). */
    var timezone: String = "Asia/Tashkent"
}
