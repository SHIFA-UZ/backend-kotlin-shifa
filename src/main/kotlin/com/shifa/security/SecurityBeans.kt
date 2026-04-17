package com.shifa.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

@Configuration
class SecurityBeans {
    // ALREADY PRESENT: BCrypt for password hashing (industry standard; never store or log plaintext passwords)
    @Bean
    fun passwordEncoder() = BCryptPasswordEncoder()
}
