package com.shifa.config

import com.shifa.shared.RequestTimingFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

@Configuration
class RequestTimingConfig {
    /** Outermost filter on the API paths, so the time includes security and rate limiting; actuator and files are not logged. */
    @Bean
    fun requestTimingFilterRegistration(): FilterRegistrationBean<RequestTimingFilter> =
        FilterRegistrationBean(RequestTimingFilter()).apply {
            addUrlPatterns("/api/*")
            order = Ordered.HIGHEST_PRECEDENCE + 10
        }
}
