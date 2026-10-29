package com.shifa.shared.config

import com.shifa.shared.security.FileUrlSigner
import com.shifa.shared.security.SignedUrlFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class FileUrlConfig {
    @Bean
    fun signedUrlFilterRegistration(signer: FileUrlSigner, appProperties: AppProperties): FilterRegistrationBean<SignedUrlFilter> =
        FilterRegistrationBean(SignedUrlFilter(signer, appProperties)).apply {
            FileUrlSigner.PROTECTED_PREFIXES.forEach { addUrlPatterns("$it*") }
            order = 10
        }
}
