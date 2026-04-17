package com.shifa.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "openai")
class OpenAiProperties {

    lateinit var apiKey: String
    lateinit var projectId: String

    lateinit var model: String
    var temperature: Double = 0.2
    var maxTokens: Int = 600
    var requestTimeoutMs: Long = 15_000

    var maxRequestsPerMinute: Int = 60
    var promptVersion: String = "1"
}
