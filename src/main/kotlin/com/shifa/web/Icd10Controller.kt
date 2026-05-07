package com.shifa.web

import com.shifa.service.Icd10Service
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/icd10")
class Icd10Controller(
    private val icd10Service: Icd10Service
) {
    /**
     * GET /api/icd10/search?q=...&limit=20
     * Returns: [{ code, title }]
     */
    @GetMapping("/search")
    fun search(
        @RequestParam("q") q: String,
        @RequestParam("limit", required = false, defaultValue = "20") limit: Int,
        @RequestParam("lang", required = false, defaultValue = "en") lang: String
    ): ResponseEntity<List<Map<String, String>>> {
        val results = icd10Service.searchByQuery(q, limit)
        val normalizedLang = lang.lowercase()
        return ResponseEntity.ok(
            results.map { r ->
                val localizedTitle =
                    when {
                        normalizedLang.startsWith("uz") && !r.titleUz.isNullOrBlank() -> r.titleUz
                        normalizedLang.startsWith("ru") && !r.titleRu.isNullOrBlank() -> r.titleRu
                        normalizedLang.startsWith("uz") && !r.titleRu.isNullOrBlank() -> r.titleRu
                        else -> r.title
                    }
                buildMap<String, String> {
                    put("code", r.code)
                    put("title", localizedTitle)
                    r.subtitle?.let { put("subtitle", it) }
                }
            }
        )
    }
}

