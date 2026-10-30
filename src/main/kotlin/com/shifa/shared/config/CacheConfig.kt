package com.shifa.shared.config

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
        /** Public reference lists read on every app start; 60 s staleness after an admin edit is accepted (Phase 6). */
        const val PROFESSIONS_CACHE = "professions"
        const val PUBLIC_CLINICS_CACHE = "publicClinics"
        /** `GET /api/public/doctors/{id}`: the same profile is opened by many patients; 60 s staleness accepted (Phase 6). */
        const val PUBLIC_DOCTOR_CACHE = "publicDoctor"
    }

    @Bean
    fun cacheManager(): CacheManager {
        val manager = CaffeineCacheManager(ICD10_SEARCH_CACHE)
        manager.setCaffeine(
            Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(10_000)
        )
        val referenceLists = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(500)
        manager.registerCustomCache(PROFESSIONS_CACHE, referenceLists.build())
        manager.registerCustomCache(PUBLIC_CLINICS_CACHE, referenceLists.build())
        manager.registerCustomCache(PUBLIC_DOCTOR_CACHE, referenceLists.build())
        return manager
    }
}

