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
import com.shifa.service.PatientDaySlotsService
import com.shifa.service.TranscriptionService
import com.shifa.web.dto.PatientCopilotAiRequest
import com.shifa.web.dto.PatientCopilotBookAppointmentRequest
import com.shifa.web.dto.PatientCopilotResolveBookingRequest
import com.shifa.web.dto.PatientCopilotSuggestDoctorsRequest
import com.shifa.web.dto.PatientCopilotSuggestFromChatRequest
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
    private val daySlotsService: PatientDaySlotsService,
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

        // If symptom terms do not map well, fall back to enabled doctors from platform
        // so users still receive practical suggestions instead of an empty result.
        if (collected.isEmpty()) {
            for (d in doctorProfiles.findAllByUserEnabled()) {
                val id = d.id ?: continue
                if (seen.add(id)) collected.add(d)
            }
        }

        // Prefer higher-rated doctors when many matches/fallback candidates are present
        val sorted = collected.sortedByDescending { doc ->
            reviewRepository.findAverageRatingByDoctorId(doc.id!!) ?: 0.0
        }

        return sorted.take(12).map { toDoctorDto(it, null) }
    }

    /**
     * Smart doctor suggestions from the whole chat history. The server asks OpenAI to infer the likely medical
     * specialty and clinical keywords, filters enabled doctors by profession (case-insensitive), and ranks them
     * by rating → soonest available slot → proximity to the patient.
     *
     * Response shape:
     *  {
     *    "needsMoreInfo": Boolean,
     *    "clarifyingQuestion": String?,    // localized follow-up when the copilot should ask for more details
     *    "specialties": List<String>,
     *    "doctors": List<DoctorDto>        // empty when needsMoreInfo == true or nothing matched
     *  }
     */
    @PostMapping("/suggest-doctors-chat")
    fun suggestDoctorsFromChat(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestBody @Valid body: PatientCopilotSuggestFromChatRequest
    ): Map<String, Any?> {
        val profile = currentPatientProfile(principal)
        val cleaned = body.messages
            .mapNotNull { msg ->
                val role = msg.role.trim().lowercase()
                val content = msg.content.trim()
                if (content.isBlank()) return@mapNotNull null
                if (role != "user" && role != "assistant" && role != "system") return@mapNotNull null
                com.shifa.web.dto.AiMessageDto(role = role, content = content)
            }
        if (cleaned.none { it.role == "user" }) {
            return mapOf(
                "needsMoreInfo" to true,
                "clarifyingQuestion" to null,
                "specialties" to emptyList<String>(),
                "doctors" to emptyList<Map<String, Any?>>()
            )
        }

        val inference = aiService.inferSpecialtiesAndClarification(cleaned, body.language)
        if (!inference.hasEnoughInfo) {
            return mapOf(
                "needsMoreInfo" to true,
                "clarifyingQuestion" to inference.clarifyingQuestion,
                "specialties" to inference.specialties,
                "doctors" to emptyList<Map<String, Any?>>()
            )
        }

        val seen = linkedSetOf<Long>()
        val candidates = mutableListOf<DoctorProfile>()

        // 1) Profession-based match (exact or substring match on DB profession field).
        val allEnabled = doctorProfiles.findAllByUserEnabled()
        for (specialty in inference.specialties) {
            val needle = specialty.lowercase().trim()
            if (needle.isBlank()) continue
            for (d in allEnabled) {
                val prof = d.profession?.lowercase()?.trim() ?: continue
                if (prof == needle || prof.contains(needle) || needle.contains(prof)) {
                    val id = d.id ?: continue
                    if (seen.add(id)) candidates.add(d)
                }
            }
        }

        // 2) Fallback: free-text search with LLM keywords if profession filter was empty.
        if (candidates.isEmpty()) {
            for (term in inference.searchTerms) {
                val needle = term.trim().take(80)
                if (needle.isBlank()) continue
                for (d in doctorProfiles.searchWithFilters(needle, null)) {
                    val id = d.id ?: continue
                    if (seen.add(id)) candidates.add(d)
                }
            }
        }

        if (candidates.isEmpty()) {
            return mapOf(
                "needsMoreInfo" to false,
                "clarifyingQuestion" to null,
                "specialties" to inference.specialties,
                "doctors" to emptyList<Map<String, Any?>>()
            )
        }

        val now = Instant.now()
        val patientLat = profile.latitude
        val patientLng = profile.longitude

        data class Scored(
            val doctor: DoctorProfile,
            val rating: Double,
            val nextSlotAt: Instant?,  // null when no free slot within window
            val distanceKm: Double?    // null when coordinates unknown
        )

        // Cap how many doctors we score to keep request fast (slot scan is O(days * rules) each).
        val capped = candidates.take(20)

        val scored = capped.map { d ->
            val rating = reviewRepository.findAverageRatingByDoctorId(d.id!!) ?: 0.0
            val next = daySlotsService.nextAvailableStartAt(d, now, lookaheadDays = 14)
            val distKm = if (patientLat != null && patientLng != null && d.latitude != null && d.longitude != null) {
                haversineKm(patientLat, patientLng, d.latitude!!, d.longitude!!)
            } else null
            Scored(d, rating, next, distKm)
        }

        // Sort: bucket rating to 0.5 so strong candidates aren't edged out by 0.1 rating differences;
        // then prefer soonest free slot; then closest distance; raw rating as final tiebreaker.
        val sorted = scored.sortedWith(
            compareByDescending<Scored> { kotlin.math.floor(it.rating * 2.0) / 2.0 }
                .thenBy { it.nextSlotAt?.toEpochMilli() ?: Long.MAX_VALUE }
                .thenBy { it.distanceKm ?: Double.MAX_VALUE }
                .thenByDescending { it.rating }
        )

        val doctors = sorted.take(12).map { s ->
            toDoctorDto(s.doctor, s.distanceKm) + mapOf(
                "nextAvailableStartAt" to s.nextSlotAt?.toString()
            )
        }

        return mapOf(
            "needsMoreInfo" to false,
            "clarifyingQuestion" to null,
            "specialties" to inference.specialties,
            "doctors" to doctors
        )
    }

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2).let { it * it }
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
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
            return mapOf(
                "booked" to false,
                "reasonCode" to "NO_CONSENT_OR_INCOMPLETE",
                "message" to "Please confirm doctor, date/time, and visit type (video or in person)."
            )
        }
        val doctorId = intent.doctorId ?: return mapOf(
            "booked" to false,
            "reasonCode" to "DOCTOR_MISSING",
            "message" to "Please choose one doctor from the suggested list."
        )
        val allowed = body.allowedDoctorIds
        if (allowed != null) {
            if (allowed.isEmpty()) {
                return mapOf(
                    "booked" to false,
                    "reasonCode" to "NO_SUGGESTED_DOCTORS",
                    "message" to "No suggested doctors on platform for this chat"
                )
            }
            if (doctorId !in allowed.toSet()) {
                return mapOf(
                    "booked" to false,
                    "reasonCode" to "DOCTOR_NOT_IN_SUGGESTED_LIST",
                    "message" to "Doctor not in suggested list"
                )
            }
        }
        val doctor = doctorProfiles.findById(doctorId).orElse(null)
        if (doctor == null || !doctor.user.enabled) {
            return mapOf(
                "booked" to false,
                "reasonCode" to "DOCTOR_NOT_FOUND",
                "message" to "Doctor not found"
            )
        }
        val prefStr = intent.preferredStartAtUtc ?: return mapOf(
            "booked" to false,
            "reasonCode" to "TIME_MISSING",
            "message" to "Please share your preferred date and time."
        )
        val video = intent.isVideo ?: return mapOf(
            "booked" to false,
            "reasonCode" to "VISIT_TYPE_MISSING",
            "message" to "Do you prefer a video visit or an in-person clinic visit?"
        )
        val preferred = try {
            Instant.parse(prefStr.trim())
        } catch (_: Exception) {
            return mapOf(
                "booked" to false,
                "reasonCode" to "INVALID_PREFERRED_TIME",
                "message" to "Invalid preferred time"
            )
        }
        if (preferred.isBefore(Instant.now().minusSeconds(60))) {
            return mapOf(
                "booked" to false,
                "reasonCode" to "PREFERRED_TIME_IN_PAST",
                "message" to "Preferred time is in the past. Please provide a future date and time."
            )
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
            "status" to booked.status,
            "paymentStatus" to booked.paymentStatus,
            "paymentAmountMinor" to booked.paymentAmountMinor,
            "paymentCurrency" to booked.paymentCurrency
        )
    }
}
