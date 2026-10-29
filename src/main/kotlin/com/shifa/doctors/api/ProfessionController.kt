package com.shifa.doctors.api


import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/public/professions")
class ProfessionController(
    private val professionRepository: com.shifa.doctors.application.Professions
) {
    
    /**
     * Get all active professions
     * @param lang Optional language code (uz/en). If provided, returns only the name in that language
     * @param search Optional search query to filter professions
     */
    @GetMapping
    @org.springframework.cache.annotation.Cacheable(cacheNames = [com.shifa.shared.config.CacheConfig.PROFESSIONS_CACHE]) // key: both params
    fun getAllProfessions(
        @RequestParam(required = false) lang: String?,
        @RequestParam(required = false) search: String?
    ): List<Map<String, Any>> {
        val professions = if (search != null && search.isNotBlank()) {
            professionRepository.searchProfessions(search)
        } else {
            professionRepository.findByIsActiveTrueOrderByCategoryAscDisplayOrderAsc()
        }
        
        return professions.map { profession ->
            val displayName = profession.getDisplayName(lang)
            mapOf(
                "id" to profession.id,
                "english" to profession.english,
                "uzbek" to profession.uzbek,
                "name" to displayName, // The name in the requested language
                "category" to (profession.category ?: ""),
                "displayOrder" to profession.displayOrder
            )
        }
    }
    
    /**
     * Get a single profession by ID
     */
    @GetMapping("/{id}")
    fun getProfessionById(
        @PathVariable id: Long,
        @RequestParam(required = false) lang: String?
    ): Map<String, Any>? {
        val profession = professionRepository.findById(id).orElse(null) ?: return null
        
        val displayName = profession.getDisplayName(lang)
        return mapOf(
            "id" to profession.id,
            "english" to profession.english,
            "uzbek" to profession.uzbek,
            "name" to displayName,
            "category" to (profession.category ?: ""),
            "displayOrder" to profession.displayOrder
        )
    }
}
