package com.shifa.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClinicClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
