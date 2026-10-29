package com.shifa

import com.shifa.config.AiRateLimitProperties
import com.shifa.config.DevSmsProperties
import com.shifa.config.OpenAiProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Spring Boot entry point. Configuration comes from application*.yml and, for secrets, the environment
 * (roadmap 3.6); the datasource is Spring's own `spring.datasource.*`, set on Railway through
 * `SPRING_DATASOURCE_*` reference variables (roadmap 2.2).
 */
@SpringBootApplication
@EnableConfigurationProperties(
    OpenAiProperties::class,
    AiRateLimitProperties::class,
    DevSmsProperties::class,
    com.shifa.config.ScribeProperties::class,
    com.shifa.ai.llm.AiRoutingProperties::class,
)
@EnableScheduling
@EnableCaching
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
