package com.shifa.web

import com.shifa.config.AppProperties
import com.shifa.domain.SubscriptionFeature
import com.shifa.security.DoctorPrincipal
import com.shifa.security.PatientPrincipal
import com.shifa.service.SubscriptionTierService
import com.shifa.service.TranscriptionFeedbackService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@RestController
class TranscriptionFeedbackController(
    private val appProps: AppProperties,
    private val feedbackService: TranscriptionFeedbackService,
    private val subscriptionTierService: SubscriptionTierService
) {

    /** Patient Shifa AI: optional QA uploads when [AppProperties.transcriptionFeedbackEnabled]. */
    @PostMapping("/api/patients/me/copilot/transcription-feedback")
    fun patientReport(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestParam transcript: String,
        @RequestParam(required = false) locale: String?,
        @RequestParam(required = false) notes: String?,
        @RequestParam(required = false) file: MultipartFile?,
    ): ResponseEntity<Map<String, String>> {
        requireEnabled()
        subscriptionTierService.requireFeature(principal.user, SubscriptionFeature.PATIENT_SHIFA_AI)
        if (transcript.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "transcript required")
        }
        val fid = feedbackService.save(
            source = TranscriptionFeedbackService.FeedbackSource.PATIENT_COPILOT,
            userId = principal.user.id,
            transcript = transcript.trim(),
            locale = locale?.trim()?.takeIf { it.isNotEmpty() },
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            audioFilename = file?.originalFilename,
            audioMimeType = file?.contentType,
            audioBytes = file?.takeUnless { it.isEmpty }?.bytes,
        )
        return ResponseEntity.ok(mapOf("id" to fid, "status" to "stored"))
    }

    /** Doctor STT: optional QA uploads when feedback is enabled. */
    @PostMapping("/api/ai/transcription-feedback")
    fun doctorReport(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam transcript: String,
        @RequestParam(required = false) locale: String?,
        @RequestParam(required = false) notes: String?,
        @RequestParam(required = false) file: MultipartFile?,
    ): ResponseEntity<Map<String, String>> {
        requireEnabled()
        if (transcript.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "transcript required")
        }
        val fid = feedbackService.save(
            source = TranscriptionFeedbackService.FeedbackSource.DOCTOR_STT,
            userId = principal.profile.user.id,
            transcript = transcript.trim(),
            locale = locale?.trim()?.takeIf { it.isNotEmpty() },
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            audioFilename = file?.originalFilename,
            audioMimeType = file?.contentType,
            audioBytes = file?.takeUnless { it.isEmpty }?.bytes,
        )
        return ResponseEntity.ok(mapOf("id" to fid, "status" to "stored"))
    }

    private fun requireEnabled() {
        if (!appProps.transcriptionFeedbackEnabled) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription feedback disabled")
        }
    }
}
