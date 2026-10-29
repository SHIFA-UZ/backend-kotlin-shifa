package com.shifa.clinics.api

import com.shifa.clinics.api.dto.PublicClinicDto
import org.springframework.data.domain.Sort
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/public/clinics")
class PublicClinicController(
    private val clinics: com.shifa.clinics.application.Clinics,
) {

    /**
     * List clinics for onboarding dropdowns (registration, etc.).
     */
    @GetMapping
    @org.springframework.cache.annotation.Cacheable(
        cacheNames = [com.shifa.shared.config.CacheConfig.PUBLIC_CLINICS_CACHE],
        key = "#search ?: ''",
    )
    fun listClinics(
        @RequestParam(required = false) search: String?,
    ): List<PublicClinicDto> {
        val all = clinics.findAll(Sort.by(Sort.Direction.ASC, "name"))
        val filtered = if (search.isNullOrBlank()) {
            all
        } else {
            val q = search.trim().lowercase()
            all.filter { it.name.lowercase().contains(q) }
        }
        return filtered.map { PublicClinicDto(id = it.id, name = it.name) }
    }
}
