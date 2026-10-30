package com.shifa.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.JacksonModule
import tools.jackson.module.kotlin.KotlinFeature
import tools.jackson.module.kotlin.KotlinModule

/**
 * Boot 4 discovers the Kotlin module on the classpath, but with the Jackson 3 module's defaults. This bean replaces the
 * discovered one (same registration id) with StrictNullChecks off: Jackson 2 accepted a null inside a non-null
 * collection, and a request that was valid for the apps yesterday must stay valid.
 */
@Configuration
class JacksonConfig {
    @Bean
    fun kotlinModule(): JacksonModule = KotlinModule.Builder().disable(KotlinFeature.StrictNullChecks).build()
}
