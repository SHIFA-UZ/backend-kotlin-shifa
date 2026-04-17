package com.shifa.config

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.CacheManager
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class CacheConfig {
    companion object {
        const val ICD10_SEARCH_CACHE = "icd10Search"
    }

    @Bean
    fun cacheManager(): CacheManager {
        val manager = CaffeineCacheManager(ICD10_SEARCH_CACHE)
        manager.setCaffeine(
            Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(10_000)
        )
        return manager
    }
}

