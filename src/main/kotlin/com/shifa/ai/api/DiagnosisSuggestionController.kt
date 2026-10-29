package com.shifa.ai.api

import com.shifa.auth.domain.SubscriptionFeature
import com.shifa.shared.security.DoctorPrincipal
import com.shifa.ai.application.DiagnosisSuggestionService
import com.shifa.payments.application.SubscriptionTierService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class SuggestIcd10Request(
    val text: String
)

@RestController
@RequestMapping("/api/ai")
class DiagnosisSuggestionController(
    private val diagnosisSuggestionService: DiagnosisSuggestionService,
    private val subscriptionTierService: SubscriptionTierService
) {
    /**
     * POST /api/ai/suggest-icd10
     * Input: { "text": "clinical notes or AI draft" }
     * Output: [ { "code": "...", "title": "..." }, ... ]
     */
    @PostMapping("/suggest-icd10")
    fun suggest(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: SuggestIcd10Request
    ): ResponseEntity<List<Map<String, Any>>> {
        subscriptionTierService.requireFeature(
            principal.profile.user,
            SubscriptionFeature.DIFFERENTIAL_DIAGNOSIS
        )
        // principal is required (doctor-only). We don't attach patient context here because
        // the mapping is purely text-based and must stay non-invasive.
        val suggestions = diagnosisSuggestionService.suggestFromText(body.text)
        return ResponseEntity.ok(
            suggestions.map { s ->
                buildMap<String, Any> {
                    put("code", s.code)
                    put("title", s.title)
                    // confidence is optional; keep it for debug/UX later without committing UI now.
                    s.confidence?.let { put("confidence", it) }
                    if (s.isTop) put("isTop", true)
                }
            }
        )
    }
}

