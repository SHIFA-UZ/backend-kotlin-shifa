package com.shifa.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.ai.PatientAiContextBuilder
import com.shifa.domain.SubscriptionFeature
import com.shifa.security.DoctorPrincipal
import com.shifa.service.AiDraftNoteService
import com.shifa.service.OpenAiResponsesService
import com.shifa.service.PatientVisitAiSummaryService
import com.shifa.service.SubscriptionTierService
import com.shifa.service.TranscriptionPurpose
import com.shifa.service.TranscriptionService
import com.shifa.web.dto.DoctorAiRequest
import kotlinx.coroutines.runBlocking
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.NoSuchElementException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files

@RestController
@RequestMapping("/api/ai")
class DoctorAiController(
    private val aiService: OpenAiResponsesService,
    private val patientAiContextBuilder: PatientAiContextBuilder,
    private val aiDraftNoteService: AiDraftNoteService,
    private val visitSummaryService: PatientVisitAiSummaryService,
    private val objectMapper: ObjectMapper,
    private val subscriptionTierService: SubscriptionTierService,
    private val transcriptionService: TranscriptionService
) {

    @PostMapping(
        "/stream",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun streamAi(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody @Valid request: DoctorAiRequest
    ): SseEmitter {

        subscriptionTierService.requireFeature(principal.profile.user, SubscriptionFeature.ASK_SHIFA_AI)

        val emitter = SseEmitter(0L) // no timeout

        val patientCtx = request.patientId?.let { patientId ->
            // Build rich, doctor-scoped patient context: demographics, accessible documents,
            // and recent appointments for this doctor with this patient.
            patientAiContextBuilder.build(patientId, principal.profile.id)
        }

        Thread {
            try {
                val fullText = StringBuilder()
                val conversation = try {
                    request.resolvedMessages()
                } catch (ex: IllegalArgumentException) {
                    throw AiStreamException("VALIDATION", ex.message ?: "Invalid AI request")
                }
                runBlocking {
                    aiService.streamDoctorAssistant(
                        doctor = principal.profile,
                        patientContext = patientCtx,
                        messages = conversation,
                        language = request.language
                    ).collect { token ->
                        fullText.append(token)
                        emitter.send(token)
                    }
                }

                val draft = aiDraftNoteService.createDraft(
                    doctorId = principal.profile.id,
                    patientId = request.patientId,
                    consultationId = request.consultationId,
                    aiResponseText = fullText.toString()
                )
                val draftPayload = objectMapper.writeValueAsString(
                    mapOf(
                        "draftId" to draft.id.toString(),
                        "draftLabel" to draft.aiLabel,
                        "canSave" to true
                    )
                )
                emitter.send(SseEmitter.event().name("draft").data(draftPayload))
                emitter.complete()
            } catch (ex: AiStreamException) {
                try {
                    val payload = objectMapper.writeValueAsString(
                        mapOf(
                            "code" to ex.code,
                            "message" to ex.message
                        )
                    )
                    emitter.send(SseEmitter.event().name("error").data(payload))
                } catch (_: Exception) { }
                emitter.complete()
            } catch (ex: Exception) {
                emitter.completeWithError(ex)
            }
        }.start()

        return emitter
    }

    @PostMapping("/draft/{id}/confirm")
    fun confirmDraft(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: java.util.UUID,
        @RequestBody(required = false) body: Map<String, Any>?
    ): ResponseEntity<Map<String, Any>> {
        return try {
            val patientId = (body?.get("patientId") as? Number)?.toLong()
            val appointmentId = (body?.get("appointmentId") as? Number)?.toLong()
            val note = aiDraftNoteService.confirmDraft(id, principal.profile.id, patientId, appointmentId)
            appointmentId?.let {
                try {
                    visitSummaryService.enqueueGeneration(it, null, force = false)
                } catch (_: Exception) {
                    // do not fail draft confirmation when visit summary generation fails
                }
            }
            ResponseEntity.ok(mapOf(
                "success" to true,
                "consultationNoteId" to (note.id ?: ""),
                "message" to "Draft confirmed and saved as consultation note."
            ))
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found")
        } catch (e: SecurityException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
    }

    @PostMapping("/draft/{id}/discard")
    fun discardDraft(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: java.util.UUID
    ): ResponseEntity<Map<String, Any>> {
        return try {
            aiDraftNoteService.discardDraft(id, principal.profile.id)
            ResponseEntity.ok(mapOf("success" to true, "message" to "Draft discarded."))
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found")
        } catch (e: SecurityException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
    }

    @GetMapping("/draft/{id}")
    fun getDraft(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: java.util.UUID
    ): ResponseEntity<Map<String, Any>> {
        val draft = aiDraftNoteService.getDraftById(id) ?: return ResponseEntity.notFound().build()
        if (draft.doctorId != principal.profile.id) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return ResponseEntity.ok(mapOf(
            "id" to draft.id.toString(),
            "aiLabel" to draft.aiLabel,
            "status" to draft.status.name,
            "createdAt" to draft.createdAt.toString(),
            "patientId" to (draft.patientId ?: ""),
            "consultationId" to (draft.consultationId ?: "")
        ))
    }

    /**
     * Doctor speech-to-text (same stack as patient copilot: [TranscriptionService]).
     * Multipart field name: [file]. Optional [languageHint] overrides profile language for Whisper (en/uz/ru).
     */
    @PostMapping("/transcribe")
    fun transcribe(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam("file") file: MultipartFile,
        @RequestParam(required = false) languageHint: String?
    ): ResponseEntity<Map<String, String>> {
        subscriptionTierService.requireFeature(principal.profile.user, SubscriptionFeature.SPEECH_TO_TEXT)
        if (file.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required")
        }
        if (file.size > 25 * 1024 * 1024) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio file exceeds 25 MB limit")
        }
        val suffix = file.originalFilename?.let { "_" + it.replace(Regex("[^a-zA-Z0-9._-]"), "_") } ?: ".m4a"
        val temp = Files.createTempFile("doctor_stt_", suffix)
        try {
            file.transferTo(temp.toFile())
            val hint = languageHint?.takeIf { it.isNotBlank() }
            val result = try {
                transcriptionService.transcribe(temp, hint, TranscriptionPurpose.VOICE_UPLOAD)
            } catch (e: TranscriptionService.TranscriptionFailedException) {
                throw ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Speech recognition failed. Please try again."
                )
            }
            return ResponseEntity.ok(mapOf("text" to result.transcript))
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
