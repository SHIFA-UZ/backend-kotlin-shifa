package com.shifa.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.ai.OutputLanguage
import com.shifa.ai.SymptomMatcher
import com.shifa.config.AppProperties
import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientProfile
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.DoctorReviewRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.PatientPrincipal
import com.shifa.service.OpenAiResponsesService
import com.shifa.service.PatientCopilotBookingService
import com.shifa.service.TranscriptionService
import com.shifa.web.dto.PatientCopilotAiRequest
import com.shifa.web.dto.PatientCopilotBookAppointmentRequest
import com.shifa.web.dto.PatientCopilotResolveBookingRequest
import com.shifa.web.dto.PatientCopilotSuggestDoctorsRequest
import jakarta.validation.Valid
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@RestController
@RequestMapping("/api/patients/me/copilot")
class PatientCopilotController(
    private val aiService: OpenAiResponsesService,
    private val transcriptionService: TranscriptionService,
    private val patientProfiles: PatientProfileRepository,
    private val doctorProfiles: DoctorProfileRepository,
    private val reviewRepository: DoctorReviewRepository,
    private val copilotBookingService: PatientCopilotBookingService,
    private val appProps: AppProperties,
    private val objectMapper: ObjectMapper
) {

    private fun currentPatientProfile(principal: PatientPrincipal): PatientProfile {
        val user = principal.user
        return patientProfiles.findByUserId(user.id)
            .orElseGet {
                user.phone?.let { patientProfiles.findByPhone(it) }?.orElse(null)
                    ?: user.email?.let { patientProfiles.findByEmail(it) }?.orElse(null)
            }
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Patient profile not found for user ${user.id}"
            )
    }

    private fun normalizeAvatarUrl(avatarUrl: String?): String? {
        val trimmed = avatarUrl?.trim() ?: return null
        val isAbs = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        return if (isAbs) trimmed
        else "${appProps.publicBaseUrl.removeSuffix("/")}/${trimmed.removePrefix("/")}"
    }

    private fun parseJsonList(jsonString: String?): List<String>? {
        if (jsonString.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(jsonString, Array<String>::class.java).toList()
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeCertificateUrl(certUrl: String?): String? {
        if (certUrl.isNullOrBlank()) return null
        val trimmed = certUrl.trim()
        val isAbs = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        return if (isAbs) trimmed
        else "${appProps.publicBaseUrl.removeSuffix("/")}/${trimmed.removePrefix("/")}"
    }

    private fun toDoctorDto(doc: DoctorProfile, distanceKm: Double? = null): Map<String, Any?> {
        val id = doc.id!!
        val avgRating = reviewRepository.findAverageRatingByDoctorId(id)
        val reviewCount = reviewRepository.countByDoctorId(id)
        val certList = parseJsonList(doc.certificates)
        val normalizedCerts: List<String>? = certList?.mapNotNull { normalizeCertificateUrl(it) }
        return mapOf(
            "id" to id,
            "firstName" to doc.firstName,
            "lastName" to doc.lastName,
            "fullName" to "${doc.firstName} ${doc.lastName}".trim(),
            "profession" to doc.profession,
            "clinic" to doc.clinic,
            "address" to doc.address,
            "phone" to doc.user.phone,
            "email" to doc.user.email,
            "photoUrl" to normalizeAvatarUrl(doc.avatarUrl),
            "averageRating" to avgRating,
            "reviewCount" to reviewCount,
            "biography" to doc.biography,
            "services" to parseJsonList(doc.services),
            "certificates" to normalizedCerts,
            "telegram" to doc.telegram,
            "instagram" to doc.instagram,
            "specializations" to null,
            "furtherInformation" to doc.biography,
            "latitude" to doc.latitude,
            "longitude" to doc.longitude,
            "distanceKm" to distanceKm,
            "locationRegion" to doc.locationRegion,
            "locationCity" to doc.locationCity,
            "locationStreetAddress" to doc.locationStreetAddress
        )
    }

    @PostMapping(
        "/stream",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun streamCopilot(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestBody @Valid request: PatientCopilotAiRequest
    ): SseEmitter {
        val emitter = SseEmitter(0L)
        val profile = currentPatientProfile(principal)

        Thread {
            try {
                val conversation = try {
                    request.resolvedMessages()
                } catch (ex: IllegalArgumentException) {
                    throw AiStreamException("VALIDATION", ex.message ?: "Invalid AI request")
                }
                runBlocking {
                    aiService.streamPatientCopilot(
                        patient = profile,
                        messages = conversation,
                        language = request.language
                    ).collect { token ->
                        emitter.send(token)
                    }
                }
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
                } catch (_: Exception) {
                }
                emitter.complete()
            } catch (ex: Exception) {
                emitter.completeWithError(ex)
            }
        }.start()

        return emitter
    }

    /**
     * Multipart field name: [file]. Optional [languageHint] overrides profile language for Whisper (en/uz/ru).
     */
    @PostMapping("/transcribe")
    fun transcribe(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestParam("file") file: MultipartFile,
        @RequestParam(required = false) languageHint: String?
    ): ResponseEntity<Map<String, String>> {
        currentPatientProfile(principal)
        if (file.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required")
        }
        if (file.size > 25 * 1024 * 1024) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio file exceeds 25 MB limit")
        }
        val suffix = file.originalFilename?.let { "_" + it.replace(Regex("[^a-zA-Z0-9._-]"), "_") } ?: ".m4a"
        val temp = Files.createTempFile("copilot_stt_", suffix)
        try {
            file.transferTo(temp.toFile())
            val hint = languageHint?.takeIf { it.isNotBlank() }
            val result = transcriptionService.transcribe(temp, hint)
            return ResponseEntity.ok(mapOf("text" to result.transcript))
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Grounded doctor suggestions from symptom text (SymptomMatcher phrases + full-text search). Real doctors from DB only.
     */
    @PostMapping("/suggest-doctors")
    fun suggestDoctors(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestBody @Valid body: PatientCopilotSuggestDoctorsRequest
    ): List<Map<String, Any?>> {
        currentPatientProfile(principal)
        val text = body.symptomsText.trim()
        if (text.isBlank()) return emptyList()

        val seen = linkedSetOf<Long>()
        val collected = mutableListOf<DoctorProfile>()

        val matches = SymptomMatcher.findMatches(text)
        for (m in matches) {
            val term = m.phrase.trim().take(80)
            if (term.isBlank()) continue
            for (d in doctorProfiles.searchWithFilters(term, null)) {
                val id = d.id ?: continue
                if (seen.add(id)) collected.add(d)
            }
        }

        if (collected.size < 8) {
            val chunk = text.take(120).trim()
            if (chunk.isNotEmpty()) {
                for (d in doctorProfiles.searchWithFilters(chunk, null)) {
                    val id = d.id ?: continue
                    if (seen.add(id)) collected.add(d)
                }
            }
        }

        // Prefer higher-rated doctors when many matches
        val sorted = collected.sortedByDescending { doc ->
            reviewRepository.findAverageRatingByDoctorId(doc.id!!) ?: 0.0
        }

        return sorted.take(12).map { toDoctorDto(it, null) }
    }

    /**
     * After a co-pilot turn, infer from the transcript whether the patient authorized auto-booking with
     * enough detail; if so, book the nearest slot and return a short follow-up line for the UI.
     */
    @PostMapping("/resolve-booking")
    fun resolveBookingFromChat(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestBody @Valid body: PatientCopilotResolveBookingRequest
    ): Map<String, Any?> {
        val profile = currentPatientProfile(principal)
        val intent = aiService.resolvePatientBookingIntent(
            messages = body.messages,
            language = body.language,
            patientTimeZone = profile.timeZone,
            allowedDoctorIds = body.allowedDoctorIds
        )
        if (!intent.bookNow || !intent.userExplicitConsentToAutoBook) {
            return mapOf("booked" to false)
        }
        val doctorId = intent.doctorId ?: return mapOf("booked" to false)
        val allowed = body.allowedDoctorIds
        if (allowed != null) {
            if (allowed.isEmpty()) {
                return mapOf("booked" to false, "message" to "No suggested doctors on platform for this chat")
            }
            if (doctorId !in allowed.toSet()) {
                return mapOf("booked" to false, "message" to "Doctor not in suggested list")
            }
        }
        val doctor = doctorProfiles.findById(doctorId).orElse(null)
        if (doctor == null || !doctor.user.enabled) {
            return mapOf("booked" to false, "message" to "Doctor not found")
        }
        val prefStr = intent.preferredStartAtUtc ?: return mapOf("booked" to false)
        val video = intent.isVideo ?: return mapOf("booked" to false)
        val preferred = try {
            Instant.parse(prefStr.trim())
        } catch (_: Exception) {
            return mapOf("booked" to false, "message" to "Invalid preferred time")
        }
        val tzRaw = profile.timeZone?.trim().orEmpty()
        val patientZone = try {
            if (tzRaw.isNotEmpty()) ZoneId.of(tzRaw) else ZoneId.of("UTC")
        } catch (_: Exception) {
            ZoneId.of("UTC")
        }
        val doctorName = "${doctor.firstName} ${doctor.lastName}".trim()
        val localWhen = ZonedDateTime.ofInstant(preferred, patientZone)
        val locale = Locale.forLanguageTag(body.language.isoCode)
        val whenFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        val modeLabel = if (video) {
            when (body.language) {
                OutputLanguage.RU -> "видео"
                OutputLanguage.UZ -> "video"
                else -> "video"
            }
        } else {
            when (body.language) {
                OutputLanguage.RU -> "очно"
                OutputLanguage.UZ -> "klinikada"
                else -> "in person"
            }
        }
        val preview = "$doctorName — ${localWhen.format(whenFmt)} ($modeLabel)"
        if (body.confirmAutoBook != true) {
            return mapOf(
                "booked" to false,
                "needsClientConfirmation" to true,
                "previewMessage" to preview
            )
        }
        return try {
            val appt = copilotBookingService.bookNearestToPreferred(
                patient = profile,
                doctorId = doctorId,
                preferredStartAt = preferred,
                isVideo = video,
                reason = "Booked via Shifa AI (chat)",
                consentConfirmed = true
            )
            val mode = if (video) "video" else "in person"
            val followUp = "Your appointment is booked with ${appt.doctorName} on ${appt.startAt} ($mode)."
            mapOf(
                "booked" to true,
                "followUpMessage" to followUp,
                "appointment" to mapOf(
                    "id" to appt.id,
                    "doctorId" to appt.doctorId,
                    "doctorName" to appt.doctorName,
                    "startAt" to appt.startAt,
                    "endAt" to appt.endAt,
                    "location" to appt.location,
                    "status" to appt.status
                )
            )
        } catch (e: ResponseStatusException) {
            mapOf("booked" to false, "message" to (e.reason ?: e.message ?: "Booking failed"))
        } catch (e: Exception) {
            mapOf("booked" to false, "message" to (e.message ?: "Booking failed"))
        }
    }

    /**
     * Auto-book the nearest available slot to the patient's preferred time (UTC).
     * Requires [PatientCopilotBookAppointmentRequest.consentConfirmed] == true.
     */
    @PostMapping("/book-appointment")
    fun bookAppointment(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestBody @Valid body: PatientCopilotBookAppointmentRequest
    ): Map<String, Any?> {
        val profile = currentPatientProfile(principal)
        val preferred = Instant.parse(body.preferredStartAt.trim())
        val booked = copilotBookingService.bookNearestToPreferred(
            patient = profile,
            doctorId = body.doctorId,
            preferredStartAt = preferred,
            isVideo = body.isVideo,
            reason = body.reason,
            consentConfirmed = body.consentConfirmed
        )
        return mapOf(
            "id" to booked.id,
            "doctorId" to booked.doctorId,
            "doctorName" to booked.doctorName,
            "doctorProfession" to booked.doctorProfession,
            "doctorClinic" to booked.doctorClinic,
            "doctorPhotoUrl" to booked.doctorPhotoUrl,
            "startAt" to booked.startAt,
            "endAt" to booked.endAt,
            "location" to booked.location,
            "reason" to booked.reason,
            "status" to booked.status
        )
    }
}
