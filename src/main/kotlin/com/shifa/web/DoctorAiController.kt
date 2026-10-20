package com.shifa.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.ai.OutputLanguage
import com.shifa.ai.PatientAiContextBuilder
import com.shifa.domain.SubscriptionFeature
import com.shifa.security.DoctorPrincipal
import com.shifa.service.AiDraftNoteService
import com.shifa.service.DoctorMorningAiService
import com.shifa.service.OpenAiResponsesService
import com.shifa.service.PatientVisitAiSummaryService
import com.shifa.service.SubscriptionTierService
import com.shifa.service.TextToSpeechService
import com.shifa.service.TranscriptionPurpose
import com.shifa.service.TranscriptionService
import com.shifa.service.VisitFactService
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
    private val morningAiService: DoctorMorningAiService,
    private val textToSpeechService: TextToSpeechService,
    private val visitFactService: VisitFactService,
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

        Thread {
            try {
                val patientCtx = request.patientId?.let { patientId ->
                    patientAiContextBuilder.build(patientId, principal.profile.id)
                }

                val fullText = StringBuilder()
                var clientConnected = true
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
                        if (!clientConnected) return@collect
                        fullText.append(token)
                        clientConnected = SseIoSupport.sendText(emitter, token)
                    }
                }

                if (!clientConnected) return@Thread

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
                if (!SseIoSupport.sendEvent(emitter, SseEmitter.event().name("draft").data(draftPayload))) {
                    return@Thread
                }
                SseIoSupport.completeQuietly(emitter)
            } catch (ex: AiStreamException) {
                try {
                    val payload = objectMapper.writeValueAsString(
                        mapOf(
                            "code" to ex.code,
                            "message" to ex.message
                        )
                    )
                    SseIoSupport.sendEvent(emitter, SseEmitter.event().name("error").data(payload))
                } catch (_: Exception) { }
                SseIoSupport.completeQuietly(emitter)
            } catch (ex: Exception) {
                SseIoSupport.finish(emitter, ex)
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
     * Spoken morning clinic session: schedule + patient briefing with tools.
     * Same SSE token shape as [/stream]; does not create a draft note.
     */
    @PostMapping(
        "/morning/stream",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun streamMorningAi(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody @Valid request: DoctorAiRequest
    ): SseEmitter {
        subscriptionTierService.requireFeature(principal.profile.user, SubscriptionFeature.ASK_SHIFA_AI)
        val emitter = SseEmitter(0L)
        Thread {
            try {
                val patientCtx = request.patientId?.let { patientId ->
                    patientAiContextBuilder.build(patientId, principal.profile.id)
                }
                val conversation = try {
                    request.resolvedMessages()
                } catch (ex: IllegalArgumentException) {
                    throw AiStreamException("VALIDATION", ex.message ?: "Invalid AI request")
                }
                var clientConnected = true
                runBlocking {
                    morningAiService.streamMorningAssistant(
                        doctor = principal.profile,
                        patientContext = patientCtx,
                        messages = conversation,
                        language = request.language,
                        selectedPatientId = request.patientId,
                        conversationId = request.conversationId,
                    ).collect { event ->
                        if (!clientConnected) return@collect
                        clientConnected = when (event) {
                            is com.shifa.service.MorningStreamEvent.Token ->
                                SseIoSupport.sendText(emitter, event.text)
                            is com.shifa.service.MorningStreamEvent.Tool -> {
                                val payload = objectMapper.writeValueAsString(
                                    mapOf("name" to event.name, "payload" to event.payload)
                                )
                                SseIoSupport.sendEvent(
                                    emitter,
                                    SseEmitter.event().name("tool").data(payload),
                                )
                            }
                        }
                    }
                }
                if (clientConnected) {
                    SseIoSupport.completeQuietly(emitter)
                }
            } catch (ex: AiStreamException) {
                try {
                    val payload = objectMapper.writeValueAsString(
                        mapOf("code" to ex.code, "message" to ex.message)
                    )
                    SseIoSupport.sendEvent(emitter, SseEmitter.event().name("error").data(payload))
                } catch (_: Exception) {
                }
                SseIoSupport.completeQuietly(emitter)
            } catch (ex: Exception) {
                SseIoSupport.finish(emitter, ex)
            }
        }.start()
        return emitter
    }

    data class SpeakRequest(
        val text: String,
        val language: OutputLanguage = OutputLanguage.UZ,
    )

    /** Server TTS for short morning replies (mp3). */
    @PostMapping("/speak")
    fun speak(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: SpeakRequest,
    ): ResponseEntity<ByteArray> {
        subscriptionTierService.requireFeature(principal.profile.user, SubscriptionFeature.ASK_SHIFA_AI)
        val text = body.text.trim()
        if (text.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required")
        }
        return try {
            val speech = textToSpeechService.speak(text, body.language)
            ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(speech.contentType))
                .header("Cache-Control", "no-store")
                .body(speech.audioBytes)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message ?: "Speech synthesis failed")
        }
    }

    @GetMapping("/visit-facts/appointment/{appointmentId}")
    fun listVisitFacts(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable appointmentId: Long,
    ): ResponseEntity<Map<String, Any>> {
        subscriptionTierService.requireFeature(principal.profile.user, SubscriptionFeature.ASK_SHIFA_AI)
        val facts = visitFactService.listForAppointment(appointmentId, principal.profile.id)
        val patterns = visitFactService.clinicPatterns(principal.profile.id)
        return ResponseEntity.ok(
            mapOf(
                "facts" to facts,
                "patterns" to patterns,
            )
        )
    }

    @GetMapping("/visit-facts/patient/{patientId}/timeline")
    fun patientTimeline(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable patientId: Long,
    ): ResponseEntity<Map<String, Any>> {
        subscriptionTierService.requireFeature(principal.profile.user, SubscriptionFeature.ASK_SHIFA_AI)
        return ResponseEntity.ok(
            mapOf(
                "timeline" to visitFactService.patientTimeline(patientId, principal.profile.id),
                "patterns" to visitFactService.clinicPatterns(principal.profile.id),
            )
        )
    }

    @GetMapping("/clinic-patterns")
    fun clinicPatterns(
        @AuthenticationPrincipal principal: DoctorPrincipal,
    ): ResponseEntity<Map<String, Any>> {
        subscriptionTierService.requireFeature(principal.profile.user, SubscriptionFeature.ASK_SHIFA_AI)
        return ResponseEntity.ok(mapOf("patterns" to visitFactService.clinicPatterns(principal.profile.id)))
    }

    data class CommitVisitFactsRequest(
        val appointmentId: Long,
        val acceptFactIds: List<Long>? = null,
        val rejectFactIds: List<Long>? = null,
    )

    /** Doctor said "document everything" — commit kept draft facts into chart + remote-care drafts. */
    @PostMapping("/visit-facts/commit")
    fun commitVisitFacts(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: CommitVisitFactsRequest,
    ): ResponseEntity<VisitFactService.CommitResult> {
        subscriptionTierService.requireFeature(principal.profile.user, SubscriptionFeature.ASK_SHIFA_AI)
        val result = visitFactService.commitForAppointment(
            appointmentId = body.appointmentId,
            doctor = principal.profile,
            acceptFactIds = body.acceptFactIds?.toSet(),
            rejectFactIds = body.rejectFactIds?.toSet(),
        )
        return ResponseEntity.ok(result)
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
            val detected = result.language?.trim()?.lowercase()?.let { raw ->
                when {
                    raw.startsWith("en") -> "en"
                    raw.startsWith("ru") -> "ru"
                    raw.startsWith("uz") -> "uz"
                    raw.startsWith("tr") || raw.startsWith("az") -> "uz"
                    else -> raw.take(2)
                }
            }
            return ResponseEntity.ok(
                buildMap {
                    put("text", result.transcript)
                    if (!detected.isNullOrBlank()) put("language", detected)
                }
            )
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
